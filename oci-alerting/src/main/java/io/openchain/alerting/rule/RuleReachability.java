package io.openchain.alerting.rule;

import io.openchain.core.event.ChainEvent.EventType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Says when a rule can <b>never</b> match given the fields and event types the deployment actually produces (a rule
 * shipped enabled that can never fire is a silent false sense of coverage). Conservative: a rule is reported only when
 * that is certain — an absent field never matches except {@code EXISTS: false}; a NOT group or an OR group with one
 * reachable branch is considered reachable.
 */
public final class RuleReachability {

    private RuleReachability() {}

    /** @return human-readable reasons; empty = the rule may match */
    public static List<String> unreachable(Rule rule, Set<String> producedFields, Set<EventType> producedTypes) {
        Set<String> reasons = new LinkedHashSet<>();
        Set<EventType> scoped = rule.scope() == null || rule.scope().eventTypes() == null ? Set.of() : rule.scope().eventTypes();
        if (!scoped.isEmpty() && scoped.stream().noneMatch(producedTypes::contains)) {
            reasons.add("none of its event types is produced: " + scoped);
        }
        if (rule.conditions() != null) dead(rule.conditions(), producedFields, producedTypes, reasons);
        if (rule.sequence() != null) {
            for (Rule.SequenceStep s : rule.sequence().steps()) {
                Set<String> step = new LinkedHashSet<>();
                if (s.condition() != null && dead(s.condition(), producedFields, producedTypes, step)) {
                    reasons.add("sequence step '" + s.name() + "' can never match (" + String.join("; ", step) + ")");
                }
            }
        }
        return new ArrayList<>(reasons);
    }

    /** True when the condition can never be satisfied; collects why. */
    private static boolean dead(Condition c, Set<String> fields, Set<EventType> types, Set<String> why) {
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
                    Set<String> branch = new LinkedHashSet<>();
                    boolean all = g.children().stream().allMatch(child -> dead(child, fields, types, branch));
                    if (all) why.addAll(branch);
                    yield all;
                }
                case NOT -> false; // NOT(never) is always true: reachable
            };
        };
    }

    private static boolean deadLeaf(Condition.Leaf l, Set<String> fields, Set<EventType> types, Set<String> why) {
        if (l.operator() == Operator.EXISTS && Boolean.FALSE.equals(l.value())) return false;
        if ("event.type".equals(l.field())) {
            List<String> wanted = switch (l.operator()) {
                case EQUALS -> List.of(String.valueOf(l.value()));
                case IN -> l.value() instanceof List<?> v ? v.stream().map(String::valueOf).toList() : List.of();
                default -> List.of();
            };
            if (!wanted.isEmpty() && wanted.stream().noneMatch(w -> types.stream().anyMatch(t -> t.name().equals(w)))) {
                why.add("event type " + String.join("/", wanted) + " is never produced");
                return true;
            }
            return false;
        }
        if (!fields.contains(l.field())) {
            why.add("field '" + l.field() + "' is never populated");
            return true;
        }
        return false;
    }
}
