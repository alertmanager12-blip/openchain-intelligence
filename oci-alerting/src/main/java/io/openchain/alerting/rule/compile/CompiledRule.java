package io.openchain.alerting.rule.compile;

import io.openchain.alerting.rule.Rule;
import io.openchain.core.event.ChainEvent;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Immutable, thread-safe compiled form of a rule. */
public final class CompiledRule {

    public enum Kind { STATELESS, AGGREGATION, SEQUENCE }

    public record CompiledStep(String name, CompiledCondition condition) {}

    public record CompiledSequence(String key, Duration within, List<CompiledStep> steps) {
        public CompiledSequence { steps = List.copyOf(steps); }
    }

    private final Rule rule;
    private final CompiledCondition condition;
    private final CompiledSequence sequence;
    private final DedupKeyTemplate dedupKey;
    private final boolean numericThreshold;

    CompiledRule(Rule rule, CompiledCondition condition, CompiledSequence sequence, DedupKeyTemplate dedupKey,
                 boolean numericThreshold) {
        this.rule = rule;
        this.condition = condition;
        this.sequence = sequence;
        this.dedupKey = dedupKey;
        this.numericThreshold = numericThreshold;
    }

    public Rule rule() { return rule; }
    public String id() { return rule.id(); }
    public String version() { return rule.version(); }
    public CompiledCondition condition() { return condition; }
    public Optional<CompiledSequence> sequence() { return Optional.ofNullable(sequence); }
    public Optional<Rule.Aggregation> aggregation() { return Optional.ofNullable(rule.aggregation()); }
    public DedupKeyTemplate dedupKey() { return dedupKey; }

    public Kind kind() {
        return rule.aggregation() != null ? Kind.AGGREGATION : sequence != null ? Kind.SEQUENCE : Kind.STATELESS;
    }

    /** True if the rule has an aggregation threshold or an ordered numeric comparison (used by permissiveness checks). */
    public boolean hasNumericThreshold() { return numericThreshold; }

    public boolean inScope(ChainEvent e) { return rule.scope().includes(e.network(), e.type()); }

    /**
     * Stateless filter: scope and top-level conditions (and, for sequence rules, at least one step). This is what
     * embedded examples check; window/sequence state is not involved.
     */
    public boolean matchesFilter(ChainEvent e, CompiledCondition.EvalStats stats) {
        if (!inScope(e) || !condition.test(e, stats)) return false;
        return sequence == null || sequence.steps().stream().anyMatch(s -> s.condition().test(e, stats));
    }

    @Override public String toString() { return "CompiledRule[" + rule.ref() + ", " + kind() + "]"; }
}
