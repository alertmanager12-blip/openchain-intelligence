package io.openchain.alerting.rule;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A validated monitoring rule (schema {@value #API_VERSION}). Instances are only produced by {@link RuleValidator},
 * so every field reference has been checked against the {@link FieldCatalog} and every limit enforced.
 * A rule is pure data: it is compiled into an immutable predicate tree, never interpreted as code.
 *
 * @param aggregation nullable; mutually exclusive with {@code sequence}
 * @param sequence    nullable; mutually exclusive with {@code aggregation}
 * @param conditions  never null (an empty AND group matches every in-scope event)
 */
public record Rule(
        String apiVersion,
        String id,
        String version,
        String name,
        String description,
        String author,
        boolean enabled,
        Scope scope,
        Condition conditions,
        Aggregation aggregation,
        Sequence sequence,
        Severity severity,
        EvidenceLevel evidenceLevel,
        List<ActionSpec> actions,
        Duration cooldown,
        String dedupKey,
        String documentation,
        BigDecimal expectedConfidence,
        List<String> tags,
        List<RuleExample> examples,
        List<RuleTestCase> tests) {

    public static final String API_VERSION = "openchain.io/rule/v1";
    public static final String DEFAULT_DEDUP_KEY = "{{rule.id}}:{{group.key}}";

    public Rule {
        actions = List.copyOf(actions);
        tags = List.copyOf(tags);
        examples = List.copyOf(examples);
        tests = List.copyOf(tests);
    }

    /** "id@version", the identifier carried by alerts and signals. */
    public String ref() { return id + "@" + version; }

    /** Empty sets mean "any". */
    public record Scope(Set<Network> networks, Set<EventType> eventTypes) {
        public Scope { networks = Set.copyOf(networks); eventTypes = Set.copyOf(eventTypes); }
        public boolean includes(Network n, EventType t) {
            return (networks.isEmpty() || networks.contains(n)) && (eventTypes.isEmpty() || eventTypes.contains(t));
        }
    }

    /**
     * Sliding event-time window aggregation per group key.
     *
     * @param field        aggregated field (null for COUNT)
     * @param balanceField reference balance for RATIO_OF_BALANCE (default {@code wallet.balance}), else null
     */
    public record Aggregation(Duration window, List<String> groupBy, AggregationFunction function, String field,
                              String balanceField, Operator operator, BigDecimal threshold) {
        public Aggregation { groupBy = List.copyOf(groupBy); }
    }

    /** Ordered steps, all matched by events sharing the same {@code key} value, within {@code within}. */
    public record Sequence(String key, Duration within, List<SequenceStep> steps) {
        public Sequence { steps = List.copyOf(steps); }
    }

    public record SequenceStep(String name, Condition condition) {}

    public record ActionSpec(ActionType type, String connectorId, Map<String, String> params) {
        public ActionSpec { params = Map.copyOf(params); }
    }

    /** A single event that must (or must not) pass the rule's stateless filter (scope + conditions). */
    public record RuleExample(String name, boolean expectMatch, TestEvent event) {}

    /** A replayed event stream with the expected number of engine triggers (and optionally grouped alerts). */
    public record RuleTestCase(String name, List<TestEvent> events, int expectTriggers, Integer expectAlerts) {
        public RuleTestCase { events = List.copyOf(events); }
    }

    /** Concrete test event; field values are already typed according to the catalog. */
    public record TestEvent(String eventId, EventType type, Network network, long blockNumber, Instant time,
                            boolean removed, Map<String, Object> fields) {
        public TestEvent { fields = Map.copyOf(fields); }
    }
}
