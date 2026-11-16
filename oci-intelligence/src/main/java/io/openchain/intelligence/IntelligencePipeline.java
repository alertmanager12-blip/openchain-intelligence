package io.openchain.intelligence;

import io.openchain.core.detection.Signal;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.TokenTransfer;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.detection.DetectionEngine;
import io.openchain.intelligence.detection.DetectionResult;
import io.openchain.intelligence.detection.EventFields;
import io.openchain.intelligence.detection.InMemorySignalRepository;
import io.openchain.intelligence.detection.SignalRepository;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.flow.NativeTokens;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.InMemoryLabelRepository;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.label.LabelRepository;
import io.openchain.intelligence.profile.WalletProfileService;
import io.openchain.intelligence.risk.InMemoryRiskScoreRepository;
import io.openchain.intelligence.risk.RiskScoringEngine;
import io.openchain.intelligence.risk.RiskScoringService;
import io.openchain.intelligence.risk.ScoringConfig;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Facade: ingest -> profile -> detection -> scoring, for both sides of each movement. Idempotent on event id.
 * Chain reorganisations ({@code removed=true}) are reverted: a value flow is removed with its effect on both profiles,
 * the signals citing its transaction are re-evaluated from the remaining evidence and the scores recomputed
 * ({@link #retractFlow}); a retracted approval or admin change drops the signals it alone justified; a retracted issuer
 * freeze or unfreeze restores the label state it replaced. See docs/P0_REORG_DESIGN.md.
 *
 * <p>Issuer freezes ({@code STABLECOIN_FREEZE}): the frozen address gets an {@link LabelCategory#ISSUER_FROZEN} label
 * (source {@code <issuer>-onchain}, reliability 1.0, CONFIRMED_BY_SOURCE, reference = tx hash, observedAt = block
 * time) and is rescored; detection is re-run for up to {@link #MAX_FREEZE_COUNTERPARTIES} addresses that exchanged
 * value with it, so their direct exposure appears. {@code STABLECOIN_UNFREEZE} removes the label and rescores the
 * address (exposure signals already raised for counterparties are kept: they describe the state at that time).
 * An event older than the last applied one for the same (address, issuer) is ignored (out-of-order replay).
 */
public final class IntelligencePipeline {
    private final FlowStore flows;
    private final WalletProfileService profiles;
    private final LabelRepository labels;
    private final SignalRepository signals;
    private final DetectionEngine detection;
    private final RiskScoringService scoring;
    private final DetectionConfig config;
    private final Clock clock;
    /** Ids of non-transfer events already processed (approvals, admin changes, freezes): bounded, oldest forgotten first. */
    private final Set<String> seenEvents = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 500_000; }
            }));
    private final io.openchain.intelligence.detection.BalanceBook balances = new io.openchain.intelligence.detection.BalanceBook();
    /** Last applied freeze/unfreeze per (address, label source), with the state it replaced (for reorg reverts). */
    private final Map<String, FreezeState> freezeStates = new java.util.concurrent.ConcurrentHashMap<>();

    public static final int MAX_FREEZE_COUNTERPARTIES = 200;

    private record FreezeState(String eventId, java.time.Instant at, AddressLabel label, FreezeState previous) {}

    /** @param duplicate true when the event had already been processed (nothing done) */
    public record Result(boolean duplicate, List<Signal> newSignals, Map<String, String> detectorErrors, List<RiskScore> scores) {
        static final Result DUPLICATE = new Result(true, List.of(), Map.of(), List.of());
    }

    public IntelligencePipeline(FlowStore flows, WalletProfileService profiles, LabelRepository labels, SignalRepository signals,
                                DetectionEngine detection, RiskScoringService scoring, DetectionConfig config, Clock clock) {
        this(flows, profiles, labels, signals, detection, scoring, config, clock, null);
    }

    /** @param canonical instance sharing of stored flows; null: the default (bounded tokens, addresses held by profiles) */
    public IntelligencePipeline(FlowStore flows, WalletProfileService profiles, LabelRepository labels, SignalRepository signals,
                                DetectionEngine detection, RiskScoringService scoring, DetectionConfig config, Clock clock,
                                io.openchain.intelligence.memory.CanonicalInstances canonical) {
        this.flows = flows; this.profiles = profiles; this.labels = labels; this.signals = signals;
        this.detection = detection; this.scoring = scoring; this.config = config; this.clock = clock;
        this.canonical = canonical != null ? canonical : new io.openchain.intelligence.memory.CanonicalInstances(
                io.openchain.intelligence.memory.CanonicalInstances.DEFAULT_TOKEN_CAPACITY, profiles::canonicalAddress);
    }

    private final io.openchain.intelligence.memory.CanonicalInstances canonical;

    /** Instance sharing of stored flows (option A): {@link #canonical(ValueFlow)} and its statistics. */
    public io.openchain.intelligence.memory.CanonicalInstances canonicalInstances() { return canonical; }

    /** An equal flow sharing the canonical token and address instances (identity only; see CanonicalInstances). */
    public ValueFlow canonical(ValueFlow f) { return canonical.flow(f); }

    /** Fully in-memory wiring with the default detectors. */
    public static IntelligencePipeline inMemory(DetectionConfig dc, ScoringConfig sc, Clock clock) {
        var flows = new InMemoryFlowStore();
        var labels = new InMemoryLabelRepository();
        var signals = new InMemorySignalRepository();
        var scores = new InMemoryRiskScoreRepository();
        var profiles = new WalletProfileService(clock, 10, scores::history);
        var scoring = new RiskScoringService(new RiskScoringEngine(sc), labels, signals, profiles::profileOrEmpty, scores, clock);
        return new IntelligencePipeline(flows, profiles, labels, signals, DetectionEngine.withDefaultDetectors(signals), scoring, dc, clock);
    }

    public FlowStore flows() { return flows; }
    public WalletProfileService profiles() { return profiles; }
    public LabelRepository labels() { return labels; }
    public SignalRepository signals() { return signals; }
    public RiskScoringService scoring() { return scoring; }
    /** Real balances reported by the ingestion (authoritative networks: real chains). */
    public io.openchain.intelligence.detection.BalanceBook balances() { return balances; }

    public Result onTransfer(TokenTransfer t) { return onFlow(ValueFlow.of(t)); }

    public Result onTransaction(ChainTransaction tx) {
        profiles.ingest(tx);
        Optional<ValueFlow> nat = ValueFlow.ofNative(tx, NativeTokens.of(tx.network()));
        return nat.map(this::onFlow).orElse(Result.DUPLICATE);
    }

    public Result onFlow(ValueFlow incoming) {
        ValueFlow f = canonical.flow(incoming); // equal flow, shared instances
        if (!flows.add(f)) return Result.DUPLICATE;
        profiles.ingest(f);
        Set<Address> subjects = new LinkedHashSet<>(List.of(f.from(), f.to()));
        Result r = run(subjects, f, null);
        if (!retractedIds.remove(f.eventId())) return r;
        // re-inclusion in another canonical block: the flow arrives after flows it explains (e.g. the forwarding of a
        // re-included receipt), so the same window is re-evaluated as for its retraction
        Replay rp = replay(subjects, f.timestamp());
        List<Signal> all = new ArrayList<>(r.newSignals()); all.addAll(rp.fresh());
        Map<String, String> errors = new LinkedHashMap<>(r.detectorErrors()); errors.putAll(rp.errors());
        Set<Address> toScore = new LinkedHashSet<>(subjects);
        rp.fresh().forEach(x -> toScore.add(x.address()));
        return new Result(false, all, errors, toScore.stream().map(scoring::rescore).toList());
    }

    /** Ids of flows retracted by a reorganisation (bounded): a later arrival of the same id is a re-inclusion. */
    private final Set<String> retractedIds = java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
            new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, false) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > 100_000; }
            }));

    /**
     * APPROVAL, CONTRACT_ADMIN_CHANGE and STABLECOIN_(UN)FREEZE events (transfers go through {@link #onTransfer}), and the
     * retraction ({@code removed=true}) of any of them or of a transfer (TOKEN_TRANSFER, NATIVE_TRANSFER).
     */
    public Result onEvent(ChainEvent e) {
        boolean freeze = e.type() == ChainEvent.EventType.STABLECOIN_FREEZE || e.type() == ChainEvent.EventType.STABLECOIN_UNFREEZE;
        if (freeze && e.removed()) {
            RetractResult r = retractFreeze(e);
            return r.applied() ? new Result(false, r.added(), r.detectorErrors(), r.scores()) : Result.DUPLICATE;
        }
        if (e.removed() && (e.type() == ChainEvent.EventType.TOKEN_TRANSFER || e.type() == ChainEvent.EventType.NATIVE_TRANSFER)) {
            RetractResult r = retractFlow(e.eventId());
            return r.applied() ? new Result(false, r.added(), r.detectorErrors(), r.scores()) : Result.DUPLICATE;
        }
        if (e.removed()) { RetractResult r = retractEvent(e); return r.applied() ? new Result(false, List.of(), Map.of(), r.scores()) : Result.DUPLICATE; }
        if (e.removed() || !seenEvents.add(e.eventId())) return Result.DUPLICATE;
        if (freeze) return applyFreeze(e);
        Optional<Address> subject = switch (e.type()) {
            case APPROVAL -> EventFields.address(e, EventFields.APPROVAL_OWNER);
            case CONTRACT_ADMIN_CHANGE -> EventFields.address(e, EventFields.ADMIN_CONTRACT);
            default -> Optional.empty();
        };
        return subject.map(a -> run(Set.of(a), null, e)).orElse(new Result(false, List.of(), Map.of(), List.of()));
    }

    private static String labelSource(ChainEvent e) {
        return EventFields.string(e, EventFields.FREEZE_ISSUER).orElse("issuer") + "-onchain";
    }

    private synchronized Result applyFreeze(ChainEvent e) {
        Optional<Address> target = EventFields.address(e, EventFields.FREEZE_ADDRESS);
        if (target.isEmpty() || e.eventTime() == null) return new Result(false, List.of(), Map.of(), List.of());
        Address a = target.get();
        String source = labelSource(e);
        String key = a + "|" + source;
        FreezeState prev = freezeStates.get(key);
        if (prev != null && e.eventTime().isBefore(prev.at())) return new Result(false, List.of(), Map.of(), List.of()); // stale
        AddressLabel label = null;
        if (e.type() == ChainEvent.EventType.STABLECOIN_FREEZE) {
            String issuer = EventFields.string(e, EventFields.FREEZE_ISSUER).orElse("issuer");
            String token = EventFields.string(e, EventFields.FREEZE_TOKEN).orElse("?");
            label = new AddressLabel(a, LabelCategory.ISSUER_FROZEN,
                    "Frozen by the issuer (" + issuer + ", " + token + " " + EventFields.string(e, EventFields.FREEZE_EVENT).orElse("blacklist") + ")",
                    source, 1.0, e.eventTime(), EvidenceLevel.CONFIRMED_BY_SOURCE, EventFields.string(e, EventFields.TX_HASH).orElse(null));
            labels.remove(a, LabelCategory.ISSUER_FROZEN, source); // a re-freeze replaces the label even if older-dated
            labels.save(label);
        } else {
            labels.remove(a, LabelCategory.ISSUER_FROZEN, source);
        }
        freezeStates.put(key, new FreezeState(e.eventId(), e.eventTime(), label, prev));
        if (label == null) return new Result(false, List.of(), Map.of(), List.of(scoring.rescore(a)));
        Set<Address> subjects = new LinkedHashSet<>();
        subjects.add(a);
        for (ValueFlow f : flows.involving(a)) {
            if (subjects.size() > MAX_FREEZE_COUNTERPARTIES) break;
            if (!f.from().equals(f.to())) subjects.add(f.counterparty(a));
        }
        return run(subjects, null, e);
    }

    /**
     * Reorg: the freeze/unfreeze is no longer on the canonical chain. The label state it replaced is restored, and the
     * signals that cite the withdrawn label (exposure of the counterparties) are removed, then detection is run again for
     * their subjects: an exposure still justified by another label comes back, the others do not (code review I6).
     */
    private synchronized RetractResult retractFreeze(ChainEvent e) {
        Optional<Address> target = EventFields.address(e, EventFields.FREEZE_ADDRESS);
        if (target.isEmpty()) return RetractResult.none();
        String source = labelSource(e);
        String key = target.get() + "|" + source;
        FreezeState st = freezeStates.get(key);
        if (st == null || !st.eventId().equals(e.eventId())) return RetractResult.none();
        seenEvents.remove(e.eventId()); // the new fork may include it again
        labels.remove(target.get(), LabelCategory.ISSUER_FROZEN, source);
        FreezeState prev = st.previous();
        if (prev != null && prev.label() != null) labels.save(prev.label());
        if (prev == null) freezeStates.remove(key); else freezeStates.put(key, prev);

        List<Signal> dependent = List.of();
        AddressLabel withdrawn = st.label(); // null for an unfreeze: it created no label, so no signal cites it
        if (withdrawn != null && withdrawn.reference() != null) {
            dependent = signals.findByEvidenceReference(withdrawn.reference()).stream()
                    .filter(x -> x.evidence().stream().anyMatch(ev -> withdrawn.reference().equals(ev.reference()) && source.equals(ev.source())))
                    .toList();
        }
        Set<String> dependentIds = new LinkedHashSet<>();
        Set<Address> subjects = new LinkedHashSet<>();
        subjects.add(target.get());
        for (Signal s : dependent) { signals.remove(s.id()); dependentIds.add(s.id()); subjects.add(s.address()); }
        List<Signal> fresh = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        if (!dependent.isEmpty()) {
            for (Address a : subjects) { // label-driven detectors run without a trigger
                DetectionResult r = detection.run(new DetectionContext(a, null, null, profiles.profileOrEmpty(a), flows, labels, config, clock.instant(), balances));
                fresh.addAll(r.newSignals()); errors.putAll(r.detectorErrors());
            }
        }
        List<Signal> recomputed = fresh.stream().filter(x -> dependentIds.contains(x.id())).toList();
        List<Signal> added = fresh.stream().filter(x -> !dependentIds.contains(x.id())).toList();
        Set<String> back = new java.util.HashSet<>(recomputed.stream().map(Signal::id).toList());
        List<Signal> removed = dependent.stream().filter(x -> !back.contains(x.id())).toList();
        Set<Address> toScore = new LinkedHashSet<>(subjects);
        fresh.forEach(x -> toScore.add(x.address()));
        return new RetractResult(true, null, 0, Set.copyOf(subjects), removed, recomputed, added, errors,
                toScore.stream().map(scoring::rescore).toList(), false);
    }

    // ------------------------------------------------------------ chain reorganisations (docs/P0_REORG_DESIGN.md)

    /** Triggers replayed per wallet when re-evaluating after a retraction (closest to the retracted flow first). */
    public static final int MAX_REPLAY_TRIGGERS = 500;
    /** Re-evaluation window around the retracted flow: the longest trigger-based detector window (structuring, 24 h). */
    public static final java.time.Duration REPLAY_WINDOW = java.time.Duration.ofHours(24);

    /**
     * Outcome of a flow retraction.
     *
     * @param applied     false when the flow was unknown or already retracted (idempotent: nothing done)
     * @param profileSidesReverted sides whose profile was reverted exactly (0..2); fewer means the flow was older than the
     *                    exact memory of that profile, whose totals then stay as they were (counted, see the design)
     * @param removed     signals that cited the transaction and are no longer justified by the remaining evidence
     * @param recomputed  signals that cited the transaction and are still justified: stored again, same id, new evidence
     * @param added       signals that did not exist before the re-evaluation (e.g. a pass-through now anchored on another receipt)
     * @param replayTruncated true when a wallet had more than {@link #MAX_REPLAY_TRIGGERS} flows to replay in the window
     */
    public record RetractResult(boolean applied, ValueFlow flow, int profileSidesReverted, Set<Address> touched,
                                List<Signal> removed, List<Signal> recomputed, List<Signal> added,
                                Map<String, String> detectorErrors, List<RiskScore> scores, boolean replayTruncated) {
        static RetractResult none() { return new RetractResult(false, null, 0, Set.of(), List.of(), List.of(), List.of(), Map.of(), List.of(), false); }
    }

    /**
     * Reverts a value flow whose block left the canonical chain: removes it (and its id, so a re-inclusion in another
     * canonical block is applied as new), reverts both profiles, forgets the balance recorded for it, then re-evaluates
     * every signal that cites its transaction, whatever its subject: such signals are removed and detection is replayed on
     * the remaining evidence, so a signal still justified comes back with the same id and updated evidence, and one that is
     * no longer justified does not. Scores of every wallet involved are recomputed. Idempotent. Callers serialise.
     */
    public synchronized RetractResult retractFlow(String eventId) {
        Optional<ValueFlow> gone = flows.remove(eventId);
        if (gone.isEmpty()) return RetractResult.none();
        ValueFlow f = gone.get();
        int sides = profiles.revert(f);
        balances.forget(eventId);
        // only flow-derived signals: an approval or admin-change signal of the same transaction stands on its own event
        // (handled by retractEvent) and would not be recreated by replaying flows
        List<Signal> dependent = signals.findByEvidenceReference(f.txHash()).stream()
                .filter(x -> x.type() != io.openchain.core.detection.SignalType.UNUSUAL_APPROVAL
                        && x.type() != io.openchain.core.detection.SignalType.OWNERSHIP_CHANGE).toList();
        Set<String> dependentIds = new LinkedHashSet<>();
        Set<Address> subjects = new LinkedHashSet<>(List.of(f.from(), f.to()));
        for (Signal s : dependent) { signals.remove(s.id()); dependentIds.add(s.id()); subjects.add(s.address()); }

        retractedIds.add(eventId);
        Replay rp = replay(subjects, f.timestamp());
        List<Signal> fresh = rp.fresh();
        Map<String, String> errors = rp.errors();
        boolean truncated = rp.truncated();
        List<Signal> recomputed = fresh.stream().filter(x -> dependentIds.contains(x.id())).toList();
        List<Signal> added = fresh.stream().filter(x -> !dependentIds.contains(x.id())).toList();
        Set<String> back = new java.util.HashSet<>(recomputed.stream().map(Signal::id).toList());
        List<Signal> removed = dependent.stream().filter(x -> !back.contains(x.id())).toList();
        Set<Address> toScore = new LinkedHashSet<>(subjects);
        fresh.forEach(x -> toScore.add(x.address()));
        List<RiskScore> scores = toScore.stream().map(scoring::rescore).toList();
        return new RetractResult(true, f, sides, Set.copyOf(subjects), removed, recomputed, added, errors, scores, truncated);
    }

    private record Replay(List<Signal> fresh, Map<String, String> errors, boolean truncated) {}

    /**
     * Re-runs detection for these wallets around {@code center}: once without a trigger (exposure, circular flow), then
     * with each of their flows within {@link #REPLAY_WINDOW} as trigger (closest first, at most
     * {@link #MAX_REPLAY_TRIGGERS}, replayed chronologically). Signal ids are deterministic: what is still stored is not
     * duplicated, what is still justified and was removed comes back with the same id.
     */
    private Replay replay(Set<Address> subjects, java.time.Instant center) {
        List<Signal> fresh = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        boolean truncated = false;
        java.time.Instant from = center.minus(REPLAY_WINDOW), to = center.plus(REPLAY_WINDOW);
        for (Address a : subjects) {
            DetectionResult r = detection.run(new DetectionContext(a, null, null, profiles.profileOrEmpty(a), flows, labels, config, clock.instant(), balances));
            fresh.addAll(r.newSignals()); errors.putAll(r.detectorErrors());
            List<ValueFlow> triggers = flows.involving(a).stream()
                    .filter(x -> !x.timestamp().isBefore(from) && !x.timestamp().isAfter(to)).distinct()
                    .sorted(java.util.Comparator.comparing((ValueFlow x) -> java.time.Duration.between(center, x.timestamp()).abs()))
                    .toList();
            if (triggers.size() > MAX_REPLAY_TRIGGERS) { truncated = true; triggers = triggers.subList(0, MAX_REPLAY_TRIGGERS); }
            for (ValueFlow t : triggers.stream().sorted(io.openchain.intelligence.flow.FlowOrder.CHRONOLOGICAL).toList()) {
                DetectionResult rt = detection.run(new DetectionContext(a, t, null, profiles.profileOrEmpty(a), flows, labels, config, clock.instant(), balances));
                fresh.addAll(rt.newSignals()); errors.putAll(rt.detectorErrors());
            }
        }
        return new Replay(fresh, errors, truncated);
    }

    /**
     * Reverts any retracted event ({@code removed=true}) and reports what changed, for callers that propagate it (rule
     * engine, recent signals): value flows via {@link #retractFlow}, approvals and admin changes via their signals.
     * Freezes restore the previous label state and withdraw the exposure signals that cited the label. Idempotent.
     */
    public RetractResult retract(ChainEvent e) {
        if (!e.removed()) return RetractResult.none();
        return switch (e.type()) {
            case TOKEN_TRANSFER, NATIVE_TRANSFER -> retractFlow(e.eventId());
            case APPROVAL, CONTRACT_ADMIN_CHANGE -> retractEvent(e);
            case STABLECOIN_FREEZE, STABLECOIN_UNFREEZE -> retractFreeze(e);
            default -> RetractResult.none();
        };
    }

    /** A retracted approval or contract admin change: its id is forgotten and the signals it alone justified are dropped. */
    private synchronized RetractResult retractEvent(ChainEvent e) {
        if (!seenEvents.remove(e.eventId())) return RetractResult.none();
        Optional<String> tx = EventFields.string(e, EventFields.TX_HASH);
        Set<Address> touched = new LinkedHashSet<>();
        List<Signal> removed = new ArrayList<>();
        if (tx.isPresent()) {
            for (Signal s : signals.findByEvidenceReference(tx.get())) {
                if (s.type() == io.openchain.core.detection.SignalType.UNUSUAL_APPROVAL
                        || s.type() == io.openchain.core.detection.SignalType.OWNERSHIP_CHANGE) {
                    signals.remove(s.id());
                    removed.add(s);
                    touched.add(s.address());
                }
            }
        }
        return new RetractResult(true, null, 0, Set.copyOf(touched), removed, List.of(), List.of(), Map.of(),
                touched.stream().map(scoring::rescore).toList(), false);
    }

    private Result run(Set<Address> subjects, ValueFlow trigger, ChainEvent event) {
        List<Signal> fresh = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Address> toScore = new LinkedHashSet<>(subjects);
        for (Address a : subjects) {
            DetectionResult r = detection.run(new DetectionContext(a, trigger, event, profiles.profileOrEmpty(a), flows, labels, config, clock.instant(), balances));
            fresh.addAll(r.newSignals());
            errors.putAll(r.detectorErrors());
            r.newSignals().forEach(s -> toScore.add(s.address()));
        }
        List<RiskScore> scores = toScore.stream().map(scoring::rescore).toList();
        return new Result(false, fresh, errors, scores);
    }
}
