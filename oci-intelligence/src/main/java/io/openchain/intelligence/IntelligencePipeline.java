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
 * Reorg-retracted value flows ({@code removed=true}) are not reverted (not supported yet); a retracted issuer freeze
 * or unfreeze IS reverted (the label state goes back to what it was before that event).
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
    private final Set<String> seenEvents = java.util.concurrent.ConcurrentHashMap.newKeySet();
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
        this.flows = flows; this.profiles = profiles; this.labels = labels; this.signals = signals;
        this.detection = detection; this.scoring = scoring; this.config = config; this.clock = clock;
    }

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

    public Result onTransfer(TokenTransfer t) { return onFlow(ValueFlow.of(t)); }

    public Result onTransaction(ChainTransaction tx) {
        profiles.ingest(tx);
        Optional<ValueFlow> nat = ValueFlow.ofNative(tx, NativeTokens.of(tx.network()));
        return nat.map(this::onFlow).orElse(Result.DUPLICATE);
    }

    public Result onFlow(ValueFlow f) {
        if (!flows.add(f)) return Result.DUPLICATE;
        profiles.ingest(f);
        Set<Address> subjects = new LinkedHashSet<>(List.of(f.from(), f.to()));
        return run(subjects, f, null);
    }

    /** APPROVAL, CONTRACT_ADMIN_CHANGE and STABLECOIN_(UN)FREEZE events (transfers go through {@link #onTransfer}). */
    public Result onEvent(ChainEvent e) {
        boolean freeze = e.type() == ChainEvent.EventType.STABLECOIN_FREEZE || e.type() == ChainEvent.EventType.STABLECOIN_UNFREEZE;
        if (freeze && e.removed()) return retractFreeze(e);
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

    /** Reorg: the freeze/unfreeze is no longer on the canonical chain; restore the state it replaced. */
    private synchronized Result retractFreeze(ChainEvent e) {
        Optional<Address> target = EventFields.address(e, EventFields.FREEZE_ADDRESS);
        if (target.isEmpty()) return Result.DUPLICATE;
        String key = target.get() + "|" + labelSource(e);
        FreezeState st = freezeStates.get(key);
        if (st == null || !st.eventId().equals(e.eventId())) return Result.DUPLICATE;
        seenEvents.remove(e.eventId()); // the new fork may include it again
        labels.remove(target.get(), LabelCategory.ISSUER_FROZEN, labelSource(e));
        FreezeState prev = st.previous();
        if (prev != null && prev.label() != null) labels.save(prev.label());
        if (prev == null) freezeStates.remove(key); else freezeStates.put(key, prev);
        return new Result(false, List.of(), Map.of(), List.of(scoring.rescore(target.get())));
    }

    private Result run(Set<Address> subjects, ValueFlow trigger, ChainEvent event) {
        List<Signal> fresh = new ArrayList<>();
        Map<String, String> errors = new LinkedHashMap<>();
        Set<Address> toScore = new LinkedHashSet<>(subjects);
        for (Address a : subjects) {
            DetectionResult r = detection.run(new DetectionContext(a, trigger, event, profiles.profileOrEmpty(a), flows, labels, config, clock.instant()));
            fresh.addAll(r.newSignals());
            errors.putAll(r.detectorErrors());
            r.newSignals().forEach(s -> toScore.add(s.address()));
        }
        List<RiskScore> scores = toScore.stream().map(scoring::rescore).toList();
        return new Result(false, fresh, errors, scores);
    }
}
