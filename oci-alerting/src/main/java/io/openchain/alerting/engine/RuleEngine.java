package io.openchain.alerting.engine;

import io.openchain.alerting.engine.EngineResult.Disposition;
import io.openchain.alerting.rule.AggregationFunction;
import io.openchain.alerting.rule.FieldCatalog;
import io.openchain.alerting.rule.FieldType;
import io.openchain.alerting.rule.Rule;
import io.openchain.alerting.rule.Rule.Aggregation;
import io.openchain.alerting.rule.compile.CompiledCondition.LeafOutcome;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.alerting.rule.compile.CompiledRule.CompiledSequence;
import io.openchain.alerting.rule.compile.FieldAccess;
import io.openchain.alerting.rule.compile.Values;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Evaluates compiled rules against the {@link ChainEvent} stream.
 * <ul>
 *   <li>Stateless rules: scope + condition tree.</li>
 *   <li>Aggregations: event-time sliding windows keyed by (rule, network|groupBy values).</li>
 *   <li>Sequences: ordered steps by key within a duration.</li>
 * </ul>
 * Event time is authoritative. A per-network watermark (max event time - allowed lateness) bounds state: late events
 * within the lateness are accounted, older ones dropped and counted. Duplicate event ids are ignored; a
 * {@code removed=true} event (chain reorg) retracts the event's contribution from every window and sequence.
 * Internal events produced by INTERNAL_EVENT actions cannot re-trigger the rule that produced them and are ignored
 * beyond {@link EngineConfig#maxInternalHops()}.
 *
 * <p>Thread-safety: {@link #process} is synchronized; scale out by partitioning the stream (e.g. per network).
 */
public final class RuleEngine {

    public static final String FIELD_HOP = "internal.hop";
    public static final String FIELD_ORIGIN_CHAIN = "internal.origin_chain";

    private final EngineConfig config;
    private final FieldCatalog catalog;
    private final EngineMetrics metrics = new EngineMetrics();
    private final EventDedupCache dedup;
    private final Map<String, Runtime> rules = new LinkedHashMap<>();
    private final Map<Network, Instant> maxEventTime = new EnumMap<>(Network.class);
    private long seq;

    public RuleEngine(EngineConfig config, FieldCatalog catalog) {
        this.config = config;
        this.catalog = catalog;
        this.dedup = new EventDedupCache(config.dedupCapacity(), config.dedupTtl(), config.clock());
    }

    public RuleEngine(EngineConfig config) { this(config, FieldCatalog.defaults()); }

    public RuleEngine() { this(EngineConfig.defaults()); }

    /** Registers (or replaces, resetting its state) a rule. */
    public synchronized void register(CompiledRule rule) { rules.put(rule.id(), new Runtime(rule)); }

    public synchronized boolean unregister(String ruleId) { return rules.remove(ruleId) != null; }

    public synchronized Optional<CompiledRule> rule(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId)).map(r -> r.rule);
    }

    public synchronized Collection<CompiledRule> rules() { return rules.values().stream().map(r -> r.rule).toList(); }

    public EngineMetrics metrics() { return metrics; }

    public EngineConfig config() { return config; }

    /** Current number of window/sequence keys held for a rule (bounded by maxGroupKeysPerRule). */
    public synchronized int groupKeyCount(String ruleId) {
        Runtime r = rules.get(ruleId);
        return r == null ? 0 : r.windows.size() + r.sequences.size();
    }

    public synchronized Optional<Instant> watermark(Network n) { return Optional.ofNullable(watermarkOf(n)); }

    public synchronized EngineResult process(ChainEvent e) {
        metrics.eventsReceived.incrementAndGet();
        if (e == null || e.eventId() == null || e.eventTime() == null || e.network() == null || e.type() == null) {
            metrics.invalidEvents.incrementAndGet();
            return EngineResult.of(Disposition.INVALID);
        }
        if (e.removed()) return retract(e);

        if (dedup.get(e.eventId()) == EventDedupCache.State.ACTIVE) {
            metrics.duplicatesIgnored.incrementAndGet();
            return EngineResult.of(Disposition.DUPLICATE);
        }
        Instant wm = watermarkOf(e.network());
        if (wm != null && e.eventTime().isBefore(wm)) {
            metrics.lateDropped.incrementAndGet();
            return EngineResult.of(Disposition.LATE_DROPPED);
        }
        Instant max = maxEventTime.get(e.network());
        if (max != null && e.eventTime().isBefore(max)) metrics.lateAccepted.incrementAndGet();
        if (max == null || e.eventTime().isAfter(max)) maxEventTime.put(e.network(), e.eventTime());
        dedup.put(e.eventId(), EventDedupCache.State.ACTIVE);
        metrics.eventsProcessed.incrementAndGet();
        maybeSweep();

        // loop protection for events emitted by INTERNAL_EVENT actions
        int hop = hopOf(e);
        if (hop > config.maxInternalHops()) {
            metrics.loopSuppressed.incrementAndGet();
            return EngineResult.of(Disposition.LOOP_SUPPRESSED);
        }
        List<String> origin = originOf(e);
        Instant watermark = watermarkOf(e.network());
        List<RuleMatch> matches = new ArrayList<>();
        for (Runtime rt : rules.values()) {
            Rule r = rt.rule.rule();
            if (!r.enabled() || !rt.rule.inScope(e)) continue;
            if (origin.contains(r.id())) { metrics.loopSuppressed.incrementAndGet(); continue; }
            RuleMatch m = switch (rt.rule.kind()) {
                case STATELESS -> stateless(rt, e, hop, origin);
                case AGGREGATION -> aggregate(rt, e, watermark, hop, origin);
                case SEQUENCE -> sequence(rt, e, watermark, hop, origin);
            };
            if (m != null) { matches.add(m); metrics.triggers.incrementAndGet(); }
        }
        return new EngineResult(Disposition.PROCESSED, matches, null);
    }

    /** Drops window/sequence state that no accepted event can touch any more, and expired dedup ids. */
    public synchronized void evictExpired() {
        dedup.purgeExpired();
        for (Runtime rt : rules.values()) {
            rt.windows.entrySet().removeIf(en -> {
                Instant wm = watermarkOf(networkOfKey(en.getKey()));
                if (wm != null) en.getValue().evictUpTo(wm.minus(rt.rule.rule().aggregation().window()));
                return en.getValue().isEmpty();
            });
            rt.sequences.entrySet().removeIf(en -> {
                Instant wm = watermarkOf(networkOfKey(en.getKey()));
                if (wm != null) en.getValue().evictUpTo(wm.minus(rt.rule.rule().sequence().within()));
                return en.getValue().isEmpty();
            });
        }
    }

    // ------------------------------------------------------------ retraction (reorg)

    private EngineResult retract(ChainEvent e) {
        if (dedup.get(e.eventId()) != EventDedupCache.State.ACTIVE) {
            metrics.retractionsIgnored.incrementAndGet();
            return EngineResult.of(Disposition.RETRACTION_IGNORED);
        }
        dedup.put(e.eventId(), EventDedupCache.State.RETRACTED);
        for (Runtime rt : rules.values()) {
            switch (rt.rule.kind()) {
                case AGGREGATION -> {
                    String key = groupKey(e, rt.rule.rule().aggregation().groupBy());
                    SlidingWindow w = key == null ? null : rt.windows.get(key);
                    if (w != null) { w.remove(e.eventId()); if (w.isEmpty()) rt.windows.remove(key); }
                }
                case SEQUENCE -> {
                    String key = groupKey(e, List.of(rt.rule.rule().sequence().key()));
                    SequenceState s = key == null ? null : rt.sequences.get(key);
                    if (s != null) { s.remove(e.eventId()); if (s.isEmpty()) rt.sequences.remove(key); }
                }
                case STATELESS -> { }
            }
        }
        metrics.retractions.incrementAndGet();
        return new EngineResult(Disposition.RETRACTED, List.of(), e.eventId());
    }

    // ------------------------------------------------------------ evaluation

    private RuleMatch stateless(Runtime rt, ChainEvent e, int hop, List<String> origin) {
        if (!rt.rule.condition().test(e, metrics)) return null;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("kind", "STATELESS");
        ev.put("conditions", explain(rt, e));
        return match(rt, e, e.network().id(), e.eventTime(), List.of(e.eventId()), ev, hop, origin);
    }

    private RuleMatch aggregate(Runtime rt, ChainEvent e, Instant watermark, int hop, List<String> origin) {
        Aggregation a = rt.rule.rule().aggregation();
        if (!rt.rule.condition().test(e, metrics)) return null;
        String key = groupKey(e, a.groupBy());
        if (key == null) { metrics.missingGroupKey.incrementAndGet(); return null; }
        BigDecimal value = null, balance = null;
        String distinct = null;
        switch (a.function()) {
            case COUNT -> { }
            case SUM, MAX, RATIO_OF_BALANCE -> {
                value = decimalField(e, a.field());
                if (value == null) { metrics.missingAggregateValue.incrementAndGet(); return null; }
                if (a.function() == AggregationFunction.RATIO_OF_BALANCE) balance = decimalField(e, a.balanceField());
            }
            case DISTINCT_COUNT -> {
                Object raw = FieldAccess.get(e, a.field());
                Object norm = raw == null ? null : Values.normalize(raw, typeOf(a.field()));
                if (norm == null) { metrics.missingAggregateValue.incrementAndGet(); return null; }
                distinct = Values.display(norm);
            }
        }
        SlidingWindow w = rt.windows.computeIfAbsent(key, k -> new SlidingWindow());
        if (watermark != null) w.evictUpTo(watermark.minus(a.window()));
        if (w.add(new SlidingWindow.Entry(e.eventId(), e.eventTime(), seq++, value, distinct, balance), config.maxEntriesPerKey()))
            metrics.windowEntriesDropped.incrementAndGet();
        SlidingWindow.Hit hit = w.evaluate(e.eventTime(), a.window(), a.function(), a.operator(), a.threshold(), config.maxEvidenceEvents());
        if (hit == null) return null;
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("kind", "AGGREGATION");
        ev.put("function", a.function().name());
        if (a.field() != null) ev.put("field", a.field());
        ev.put("value", Values.display(hit.value()));
        ev.put("operator", a.operator().name());
        ev.put("threshold", Values.display(a.threshold()));
        ev.put("window", a.window().toString());
        ev.put("windowStartExclusive", hit.start().toString());
        ev.put("windowEnd", hit.end().toString());
        ev.put("eventCount", hit.count());
        ev.put("groupBy", a.groupBy());
        ev.put("conditions", explain(rt, e));
        return match(rt, e, key, hit.end(), hit.eventIds(), ev, hop, origin);
    }

    private RuleMatch sequence(Runtime rt, ChainEvent e, Instant watermark, int hop, List<String> origin) {
        CompiledSequence s = rt.rule.sequence().orElseThrow();
        if (!rt.rule.condition().test(e, metrics)) return null;
        int[] matching = new int[s.steps().size()];
        int count = 0;
        for (int i = 0; i < s.steps().size(); i++) if (s.steps().get(i).condition().test(e, metrics)) matching[count++] = i;
        if (count == 0) return null;
        String key = groupKey(e, List.of(s.key()));
        if (key == null) { metrics.missingGroupKey.incrementAndGet(); return null; }
        SequenceState st = rt.sequences.computeIfAbsent(key, k -> new SequenceState(s.steps().size()));
        if (watermark != null) st.evictUpTo(watermark.minus(s.within()));
        SequenceState.Entry entry = new SequenceState.Entry(e.eventId(), e.eventTime());
        int perStep = Math.max(1, config.maxEntriesPerKey() / s.steps().size());
        for (int k = 0; k < count; k++) if (st.add(matching[k], entry, perStep)) metrics.windowEntriesDropped.incrementAndGet();
        for (int k = 0; k < count; k++) {
            List<SequenceState.Entry> chain = st.completeWith(matching[k], entry, s.within());
            if (chain == null) continue;
            if (st.isEmpty()) rt.sequences.remove(key);
            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("kind", "SEQUENCE");
            ev.put("key", s.key());
            ev.put("within", s.within().toString());
            List<String> steps = new ArrayList<>();
            for (int i = 0; i < chain.size(); i++)
                steps.add(s.steps().get(i).name() + "=" + chain.get(i).eventId() + "@" + chain.get(i).time());
            ev.put("steps", steps);
            ev.put("span", java.time.Duration.between(chain.getFirst().time(), chain.getLast().time()).toString());
            Instant end = chain.getLast().time();
            return match(rt, e, key, end, chain.stream().map(SequenceState.Entry::eventId).toList(), ev, hop, origin);
        }
        return null;
    }

    private RuleMatch match(Runtime rt, ChainEvent e, String groupKey, Instant time, List<String> ids,
                            Map<String, Object> evidence, int hop, List<String> origin) {
        Rule r = rt.rule.rule();
        evidence.put("evidenceLevel", r.evidenceLevel().name());
        String dedupKey = rt.rule.dedupKey().render(r.id(), r.version(), groupKey, e);
        return new RuleMatch(r.id(), r.version(), r.name(), r.severity(), r.evidenceLevel(), e.network(), groupKey,
                dedupKey, e.eventId(), time, ids, evidence, origin, hop, io.openchain.alerting.alert.AlertSubject.of(e));
    }

    private List<String> explain(Runtime rt, ChainEvent e) {
        List<LeafOutcome> out = new ArrayList<>();
        rt.rule.condition().explain(e, out);
        return out.stream().map(LeafOutcome::toString).toList();
    }

    // ------------------------------------------------------------ helpers

    private String groupKey(ChainEvent e, List<String> fields) {
        StringBuilder sb = new StringBuilder(e.network().id());
        for (String f : fields) {
            Object v = FieldAccess.get(e, f);
            if (v == null) return null;
            Object norm = Values.normalize(v, typeOf(f));
            sb.append('|').append(Values.display(norm == null ? v : norm));
        }
        return sb.toString();
    }

    private static Network networkOfKey(String key) {
        int i = key.indexOf('|');
        return Network.fromId(i < 0 ? key : key.substring(0, i));
    }

    private FieldType typeOf(String field) {
        return catalog.find(field).map(FieldCatalog.FieldDef::type).orElse(FieldType.STRING);
    }

    private BigDecimal decimalField(ChainEvent e, String field) {
        Object raw = FieldAccess.get(e, field);
        if (raw == null) return null;
        BigDecimal d = Values.toDecimal(raw);
        if (d == null) metrics.typeMismatch();
        return d;
    }

    private Instant watermarkOf(Network n) {
        Instant max = maxEventTime.get(n);
        return max == null ? null : max.minus(config.allowedLateness());
    }

    private static int hopOf(ChainEvent e) {
        BigDecimal h = Values.toDecimal(e.fields().getOrDefault(FIELD_HOP, 0L));
        return h == null ? Integer.MAX_VALUE : h.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
    }

    private static List<String> originOf(ChainEvent e) {
        Object o = e.fields().get(FIELD_ORIGIN_CHAIN);
        if (!(o instanceof String s) || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private void maybeSweep() {
        if (config.sweepEveryEvents() > 0 && metrics.eventsProcessed.get() % config.sweepEveryEvents() == 0) evictExpired();
    }

    /** Per-rule state with LRU-bounded key maps. */
    private final class Runtime {
        final CompiledRule rule;
        final Map<String, SlidingWindow> windows = lru();
        final Map<String, SequenceState> sequences = lru();

        Runtime(CompiledRule rule) { this.rule = rule; }

        private <V> Map<String, V> lru() {
            int max = config.maxGroupKeysPerRule();
            return new LinkedHashMap<>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                    if (size() > max) { metrics.groupKeysEvicted.incrementAndGet(); return true; }
                    return false;
                }
            };
        }
    }
}
