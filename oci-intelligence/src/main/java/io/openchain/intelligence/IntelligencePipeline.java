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
import io.openchain.intelligence.label.InMemoryLabelRepository;
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
 * Reorg-retracted events ({@code removed=true}) are not applied (retraction is not supported yet).
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

    /** APPROVAL and CONTRACT_ADMIN_CHANGE events (transfers should go through {@link #onTransfer}). */
    public Result onEvent(ChainEvent e) {
        if (e.removed() || !seenEvents.add(e.eventId())) return Result.DUPLICATE;
        Optional<Address> subject = switch (e.type()) {
            case APPROVAL -> EventFields.address(e, EventFields.APPROVAL_OWNER);
            case CONTRACT_ADMIN_CHANGE -> EventFields.address(e, EventFields.ADMIN_CONTRACT);
            default -> Optional.empty();
        };
        return subject.map(a -> run(Set.of(a), null, e)).orElse(new Result(false, List.of(), Map.of(), List.of()));
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
