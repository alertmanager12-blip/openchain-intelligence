package io.openchain.alerting.rule;

import io.openchain.core.event.ChainEvent.EventType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Says when a rule can <b>never</b> match given the fields and event types the deployment actually produces (a rule
 * shipped enabled that can never fire is a silent false sense of coverage). Conservative: a rule is reported only when
 * that is certain — an absent field never matches except {@code EXISTS: false}; a NOT group or an OR group with one
 * reachable branch is considered reachable.
 */
public final class RuleReachability {

    private RuleReachability() {}

    /**
     * One reason a rule can never match: a stable {@code code} (the API's {@code unreachable.*} ids) with its
     * placeholder values, and the English sentence those make.
     */
    public record Reason(String code, Map<String, Object> params, String message) {
        public Reason {
            params = Map.copyOf(params);
        }
    }

    /** @return human-readable reasons; empty = the rule may match */
    public static List<String> unreachable(Rule rule, Set<String> producedFields, Set<EventType> producedTypes) {
        return reasons(rule, producedFields, producedTypes).stream().map(Reason::message).toList();
    }

    /** The same reasons, structured (code, params and English message), in the same order. */
    public static List<Reason> reasons(Rule rule, Set<String> producedFields, Set<EventType> producedTypes) {
        Set<Reason> reasons = new LinkedHashSet<>();
        Set<EventType> scoped = rule.scope() == null || rule.scope().eventTypes() == null ? Set.of() : rule.scope().eventTypes();
        if (!scoped.isEmpty() && scoped.stream().noneMatch(producedTypes::contains)) {
            reasons.add(reason("unreachable.event_types_not_produced", "none of its event types is produced: " + scoped, "types", scoped.toString()));
        }
        if (rule.conditions() != null) dead(rule.conditions(), producedFields, producedTypes, reasons);
        if (rule.sequence() != null) {
            for (Rule.SequenceStep s : rule.sequence().steps()) {
                Set<Reason> step = new LinkedHashSet<>();
                if (s.condition() != null && dead(s.condition(), producedFields, producedTypes, step)) {
                    String inner = String.join("; ", step.stream().map(Reason::message).toList());
                    reasons.add(reason("unreachable.sequence_step", "sequence step '" + s.name() + "' can never match (" + inner + ")",
                            "step", s.name(), "reasons", inner));
                }
            }
        }
        return new ArrayList<>(reasons);
    }

    private static Reason reason(String code, String message, Object... kv) {
        Map<String, Object> p = new LinkedHashMap<>();
        if (kv.length % 2 != 0) throw new IllegalArgumentException("key/value pairs expected, got " + kv.length + " argument(s)");
        for (int i = 0; i + 1 < kv.length; i += 2) p.put((String) kv[i], kv[i + 1]);
        return new Reason(code, p, message);
    }

    /** True when the condition can never be satisfied; collects why. */
    private static boolean dead(Condition c, Set<String> fields, Set<EventType> types, Set<Reason> why) {
        return switch (c) {
            case Condition.Leaf l -> deadLeaf(l, fields, types, why);
            case Condition.Group g -> switch (g.logic()) {
                case AND -> {
                    boolean any = false;
                    for (Condition child : g.children()) any |= dead(child, fields, types, why);
                    yield any;
                }
                case OR -> {
                    if (g.children().isEmpty()) yield false;
                    Set<Reason> branch = new LinkedHashSet<>();
                    boolean all = g.children().stream().allMatch(child -> dead(child, fields, types, branch));
                    if (all) why.addAll(branch);
                    yield all;
                }
                case NOT -> false; // NOT(never) is always true: reachable
            };
        };
    }

    private static boolean deadLeaf(Condition.Leaf l, Set<String> fields, Set<EventType> types, Set<Reason> why) {
        if (l.operator() == Operator.EXISTS && Boolean.FALSE.equals(l.value())) return false;
        if ("event.type".equals(l.field())) {
            List<String> wanted = switch (l.operator()) {
                case EQUALS -> List.of(String.valueOf(l.value()));
                case IN -> l.value() instanceof List<?> v ? v.stream().map(String::valueOf).toList() : List.of();
                default -> List.of();
            };
            if (!wanted.isEmpty() && wanted.stream().noneMatch(w -> types.stream().anyMatch(t -> t.name().equals(w)))) {
                String t = String.join("/", wanted);
                why.add(reason("unreachable.event_type_not_produced", "event type " + t + " is never produced", "type", t));
                return true;
            }
            return false;
        }
        if (!fields.contains(l.field())) {
            why.add(reason("unreachable.field_not_produced", "field '" + l.field() + "' is never populated", "field", l.field()));
            return true;
        }
        return false;
    }
}
