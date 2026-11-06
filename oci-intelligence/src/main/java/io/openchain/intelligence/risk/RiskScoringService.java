package io.openchain.intelligence.risk;

import io.openchain.core.model.Address;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.detection.SignalRepository;
import io.openchain.intelligence.label.LabelRepository;
import io.openchain.intelligence.profile.WalletProfile;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Computes scores from stored labels/signals/profile and keeps a history (a new entry only when score or level changes). */
public final class RiskScoringService {
    private final RiskScoringEngine engine;
    private final LabelRepository labels;
    private final SignalRepository signals;
    private final Function<Address, WalletProfile> profiles;
    private final RiskScoreRepository history;
    private final Clock clock;

    public RiskScoringService(RiskScoringEngine engine, LabelRepository labels, SignalRepository signals,
                              Function<Address, WalletProfile> profiles, RiskScoreRepository history, Clock clock) {
        this.engine = engine; this.labels = labels; this.signals = signals;
        this.profiles = profiles; this.history = history; this.clock = clock;
    }

    public RiskScore rescore(Address a) {
        RiskScore s = engine.score(a, profiles.apply(a), labels.findByAddress(a), signals.findByAddress(a), clock.instant());
        Optional<RiskScore> prev = history.latest(a);
        if (prev.isEmpty() || prev.get().score() != s.score() || prev.get().level() != s.level()) history.append(s);
        return s;
    }

    public List<RiskScore> history(Address a) { return history.history(a); }

    public Optional<RiskScore> latest(Address a) { return history.latest(a); }
}
