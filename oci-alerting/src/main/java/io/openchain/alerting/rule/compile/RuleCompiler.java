package io.openchain.alerting.rule.compile;

import io.openchain.alerting.rule.Condition;
import io.openchain.alerting.rule.Condition.Group;
import io.openchain.alerting.rule.Condition.Leaf;
import io.openchain.alerting.rule.FieldCatalog;
import io.openchain.alerting.rule.FieldType;
import io.openchain.alerting.rule.Operator;
import io.openchain.alerting.rule.Rule;
import io.openchain.alerting.rule.RuleLimits;
import io.openchain.core.event.ChainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Compiles a validated {@link Rule} into an immutable predicate tree. Each leaf becomes a closed, pre-built Java
 * predicate over the normalised field value: no expression language, no reflection, no code from the rule document.
 */
public final class RuleCompiler {

    private final FieldCatalog catalog;
    private final RuleLimits limits;

    public RuleCompiler(FieldCatalog catalog, RuleLimits limits) {
        this.catalog = catalog;
        this.limits = limits;
    }

    public RuleCompiler() { this(FieldCatalog.defaults(), RuleLimits.defaults()); }

    public CompiledRule compile(Rule rule) {
        CompiledCondition root = condition(rule.conditions());
        CompiledRule.CompiledSequence seq = null;
        if (rule.sequence() != null) {
            seq = new CompiledRule.CompiledSequence(rule.sequence().key(), rule.sequence().within(),
                    rule.sequence().steps().stream()
                            .map(s -> new CompiledRule.CompiledStep(s.name(), condition(s.condition()))).toList());
        }
        return new CompiledRule(rule, root, seq, DedupKeyTemplate.parse(rule.dedupKey(), catalog), hasNumericThreshold(rule));
    }

    public CompiledCondition condition(Condition c) {
        return switch (c) {
            case Group g -> group(g);
            case Leaf l -> leaf(l);
        };
    }

    private static boolean hasNumericThreshold(Rule rule) {
        if (rule.aggregation() != null) return true;
        if (numericLeaf(rule.conditions())) return true;
        return rule.sequence() != null && rule.sequence().steps().stream().anyMatch(s -> numericLeaf(s.condition()));
    }

    private static boolean numericLeaf(Condition c) {
        return switch (c) {
            case Group g -> g.logic() != Condition.Logic.NOT && g.children().stream().anyMatch(RuleCompiler::numericLeaf);
            case Leaf l -> l.operator().ordered();
        };
    }

    // ------------------------------------------------------------ groups

    private CompiledCondition group(Group g) {
        CompiledCondition[] children = g.children().stream().map(this::condition).toArray(CompiledCondition[]::new);
        return switch (g.logic()) {
            case AND -> new CompiledCondition() {
                @Override public boolean test(ChainEvent e, EvalStats s) {
                    for (CompiledCondition c : children) if (!c.test(e, s)) return false;
                    return true;
                }
                @Override public void explain(ChainEvent e, List<LeafOutcome> out) { for (CompiledCondition c : children) c.explain(e, out); }
            };
            case OR -> new CompiledCondition() {
                @Override public boolean test(ChainEvent e, EvalStats s) {
                    for (CompiledCondition c : children) if (c.test(e, s)) return true;
                    return false;
                }
                @Override public void explain(ChainEvent e, List<LeafOutcome> out) { for (CompiledCondition c : children) c.explain(e, out); }
            };
            case NOT -> new CompiledCondition() {
                @Override public boolean test(ChainEvent e, EvalStats s) { return !children[0].test(e, s); }
                @Override public void explain(ChainEvent e, List<LeafOutcome> out) {
                    List<LeafOutcome> inner = new java.util.ArrayList<>();
                    children[0].explain(e, inner);
                    for (LeafOutcome o : inner)
                        out.add(new LeafOutcome(o.field(), "NOT " + o.operator(), o.expected(), o.actual(), !o.matched()));
                }
            };
        };
    }

    // ------------------------------------------------------------ leaves

    private CompiledCondition leaf(Leaf leaf) {
        FieldType type = catalog.find(leaf.field())
                .orElseThrow(() -> new IllegalArgumentException("field not in catalog: " + leaf.field())).type();
        Operator op = leaf.operator();
        boolean ic = leaf.ignoreCase();
        Object lit = ic ? lower(leaf.value()) : leaf.value();
        String expected = describe(leaf.value());
        String field = leaf.field();

        if (op == Operator.EXISTS) {
            boolean mustExist = (Boolean) leaf.value();
            return new LeafNode(field, op, expected, type, ic, null) {
                @Override public boolean test(ChainEvent e, EvalStats s) { return (FieldAccess.get(e, field) != null) == mustExist; }
            };
        }
        Predicate<Object> p = switch (op) {
            case EQUALS -> v -> eq(v, lit);
            case NOT_EQUALS -> v -> !eq(v, lit);
            case GREATER_THAN -> v -> cmp(v, lit) > 0;
            case GREATER_THAN_OR_EQUAL -> v -> cmp(v, lit) >= 0;
            case LESS_THAN -> v -> cmp(v, lit) < 0;
            case LESS_THAN_OR_EQUAL -> v -> cmp(v, lit) <= 0;
            case BETWEEN -> {
                List<?> range = (List<?>) lit;
                Object lo = range.get(0), hi = range.get(1);
                yield v -> cmp(v, lo) >= 0 && cmp(v, hi) <= 0;
            }
            case IN, NOT_IN -> {
                Set<Object> set = type.numeric() ? new TreeSet<>() : new HashSet<>();
                set.addAll((List<?>) lit);
                Set<Object> frozen = type.numeric() ? java.util.Collections.unmodifiableSet(set) : Set.copyOf(set);
                yield op == Operator.IN ? frozen::contains : v -> !frozen.contains(v);
            }
            case CONTAINS -> { String needle = (String) lit; yield v -> ((String) v).contains(needle); }
            case STARTS_WITH -> { String prefix = (String) lit; yield v -> ((String) v).startsWith(prefix); }
            case MATCHES -> {
                SafeRegex re = SafeRegex.compile((String) lit, limits.maxRegexLength(), limits.maxRegexInputLength(), limits.maxRegexSteps());
                yield v -> re.matches((String) v); // BudgetExceeded handled in LeafNode
            }
            case EXISTS -> throw new IllegalStateException();
        };
        return new LeafNode(field, op, expected, type, ic, p);
    }

    private static class LeafNode implements CompiledCondition {
        final String field; final Operator op; final String expected; final FieldType type; final boolean ic;
        final Predicate<Object> predicate;

        LeafNode(String field, Operator op, String expected, FieldType type, boolean ic, Predicate<Object> predicate) {
            this.field = field; this.op = op; this.expected = expected; this.type = type; this.ic = ic; this.predicate = predicate;
        }

        @Override public boolean test(ChainEvent e, EvalStats s) {
            Object raw = FieldAccess.get(e, field);
            if (raw == null) return false; // an absent field never matches (except EXISTS: false)
            Object v = Values.normalize(raw, type);
            if (v == null) { s.typeMismatch(); return false; }
            if (ic && v instanceof String str) v = str.toLowerCase(Locale.ROOT);
            try {
                return predicate.test(v);
            } catch (SafeRegex.BudgetExceeded ex) {
                s.regexBudgetExceeded();
                return false;
            }
        }

        @Override public void explain(ChainEvent e, List<LeafOutcome> out) {
            Object raw = FieldAccess.get(e, field);
            out.add(new LeafOutcome(field, op.name(), expected, raw == null ? "<absent>" : Values.display(raw), test(e, EvalStats.NOOP)));
        }
    }

    private static Object lower(Object v) {
        if (v instanceof String s) return s.toLowerCase(Locale.ROOT);
        if (v instanceof List<?> l) return l.stream().map(RuleCompiler::lower).toList();
        return v;
    }

    private static boolean eq(Object v, Object lit) {
        if (v instanceof BigDecimal a && lit instanceof BigDecimal b) return a.compareTo(b) == 0;
        return v.equals(lit);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int cmp(Object v, Object lit) {
        if (v instanceof BigDecimal a && lit instanceof BigDecimal b) return a.compareTo(b);
        if (v instanceof Instant a && lit instanceof Instant b) return a.compareTo(b);
        return ((Comparable) v).compareTo(lit);
    }

    private static String describe(Object v) {
        if (v instanceof List<?> l) return l.stream().map(RuleCompiler::describe).toList().toString();
        return Values.display(v);
    }
}
