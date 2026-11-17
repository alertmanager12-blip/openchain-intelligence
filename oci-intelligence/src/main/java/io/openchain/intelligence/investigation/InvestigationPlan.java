package io.openchain.intelligence.investigation;

import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.exposure.ExposurePaths;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelRepository;
import io.openchain.intelligence.relations.WalletRelations;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceEngine;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The deterministic investigation plan (ADR-0022 amendment 2), version {@value #VERSION}. Pure: it reads only the
 * {@link Trace.Source} it is given (the caller records it, so the run can be replayed on frozen evidence) and the inputs
 * computed by the caller (coverage, signals), and returns typed assertions in a fixed order.
 * <p>Levels (ADR-0023 amendment 1): facts about the flows and about this run are {@code OBSERVED}; a label is
 * {@code CONFIRMED} only when its source confirms it ({@code CONFIRMED_BY_SOURCE}), else {@code INFERRED}; exposure,
 * attribution, patterns, relations and detector signals are {@code INFERRED}, whatever the level of the label behind them.
 */
public final class InvestigationPlan {
    private InvestigationPlan() {}

    public static final String VERSION = "plan@1";

    public enum Level { OBSERVED, INFERRED, CONFIRMED }

    public enum Type { COVERAGE, LABEL, DIRECT_EXPOSURE, IGNORED_TRANSFER, TRACE_ATTRIBUTION, TRACE_STOP, PATTERN, RELATION, SIGNAL, STEP_LIMIT }

    /**
     * One typed statement with its evidence. No free text: the report words it from a template per type. {@code params}
     * holds exact values as strings (amounts in raw units with their token key), sorted.
     */
    public record Assertion(String id, Type type, String step, Level level, String subject, Map<String, String> params,
                            List<String> evidence) {}

    /** Coverage of the subject as computed by the caller (P0-B). */
    public record Coverage(String state, Long fromBlock, Long toBlock, List<String> reasons) {}

    public record Inputs(Address subject, Coverage coverage, List<Signal> signals, DetectionConfig.Exposure exposure,
                         WalletRelations.Config relations, Trace.Limits traceLimits) {}

    public static final List<String> STEPS = List.of("coverage", "labels", "exposure", "traces", "patterns", "relations", "signals");

    public static List<Assertion> run(Inputs in, Trace.Source source) {
        List<Assertion> out = new ArrayList<>();
        Address s = in.subject();
        String subject = s.network().id() + ":" + s.value();

        // 1. coverage
        Map<String, String> cov = new TreeMap<>();
        cov.put("state", in.coverage().state());
        if (in.coverage().fromBlock() != null) cov.put("fromBlock", in.coverage().fromBlock().toString());
        if (in.coverage().toBlock() != null) cov.put("toBlock", in.coverage().toBlock().toString());
        cov.put("reasons", String.join(" | ", in.coverage().reasons()));
        out.add(assertion(Type.COVERAGE, "coverage", Level.OBSERVED, subject, cov, List.of()));

        // 2. labels of the subject
        for (AddressLabel l : sortedLabels(source.labels(s))) {
            Level lvl = l.evidenceLevel() == Signal.EvidenceLevel.CONFIRMED_BY_SOURCE ? Level.CONFIRMED : Level.INFERRED;
            out.add(assertion(Type.LABEL, "labels", lvl, subject, Map.of("category", l.category().name(), "source", l.source(),
                    "observedAt", String.valueOf(l.observedAt())), List.of("label:" + l.source() + ":" + l.address().value())));
        }

        // 3. direct exposure (same qualification as the detector and /exposure)
        FlowStore store = new SourceFlowStore(source);
        LabelRepository labels = new SourceLabels(source);
        for (ExposurePaths.Direct d : ExposurePaths.direct(s, store, labels, in.exposure())) {
            AddressLabel l = d.label();
            for (ValueFlow f : d.ignored()) {
                out.add(assertion(Type.IGNORED_TRANSFER, "exposure", Level.OBSERVED, subject, Map.of("counterparty", l.address().value(),
                        "category", l.category().name(), "reason", f.token().isUnverified() ? "unverified_token" : "below_dust_threshold",
                        "tokenKey", Trace.tokenKey(f.token()), "raw", f.rawAmount().toString()), List.of(f.eventId())));
            }
            if (!d.exposed()) continue;
            Map<String, BigInteger[]> perToken = new TreeMap<>();
            for (ValueFlow f : d.flows()) {
                BigInteger[] io = perToken.computeIfAbsent(Trace.tokenKey(f.token()), k -> new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO});
                if (f.to().equals(s)) io[0] = io[0].add(f.rawAmount()); else io[1] = io[1].add(f.rawAmount());
            }
            Map<String, String> p = new TreeMap<>();
            p.put("counterparty", l.address().value());
            p.put("category", l.category().name());
            p.put("labelSource", l.source());
            perToken.forEach((k, io) -> { p.put("received:" + k, io[0].toString()); p.put("sent:" + k, io[1].toString()); });
            out.add(assertion(Type.DIRECT_EXPOSURE, "exposure", Level.INFERRED, subject, p, d.flows().stream().map(ValueFlow::eventId).sorted().toList()));
        }

        // 4 and 5. traces out and in, their stops and patterns
        for (Trace.Direction dir : Trace.Direction.values()) {
            Trace.Result r = TraceEngine.run(Trace.Request.of(s, dir).withLimits(in.traceLimits()), source);
            String step = "traces";
            for (Trace.Attribution a : r.attributions()) {
                Map<String, String> p = new TreeMap<>(Map.of("direction", dir.name(), "address", a.address().value(), "tokenKey", a.tokenKey(),
                        "depth", String.valueOf(a.depth()), "low", a.low().toString(), "high", a.high().toString(), "partial", String.valueOf(a.partial()),
                        "method", Trace.Method.PROPORTIONAL.name()));
                List<String> ev = r.edges().stream().filter(e -> (dir == Trace.Direction.FORWARD ? e.to() : e.from()).equals(a.address())
                        && e.tokenKey().equals(a.tokenKey())).map(Trace.Edge::eventId).sorted().toList();
                out.add(assertion(Type.TRACE_ATTRIBUTION, step, Level.INFERRED, subject, p, ev));
            }
            for (Trace.Stop st : r.stops()) {
                out.add(assertion(Type.TRACE_STOP, step, Level.OBSERVED, subject, new TreeMap<>(Map.of("direction", dir.name(),
                        "address", st.address().value(), "reason", st.reason().name(), "tokenKey", st.tokenKey() == null ? "" : st.tokenKey())), List.of()));
            }
            for (Trace.Finding f : r.findings()) {
                out.add(assertion(Type.PATTERN, "patterns", Level.INFERRED, subject, new TreeMap<>(Map.of("direction", dir.name(), "pattern", f.type().name(),
                        "addresses", String.join(",", f.addresses().stream().map(Address::value).toList()))), f.eventIds().stream().sorted().toList()));
            }
        }

        // 6. relations
        for (WalletRelations.Relation rel : WalletRelations.find(s, source, in.relations()).relations()) {
            out.add(assertion(Type.RELATION, "relations", Level.INFERRED, subject, new TreeMap<>(Map.of("other", rel.other().value(),
                    "relation", rel.type().name(), "confidence", BigDecimal.valueOf(rel.confidence()).toPlainString(),
                    "via", rel.via() == null ? "" : rel.via().value())), rel.eventIds().stream().sorted().toList()));
        }

        // 7. signals already detected
        List<Signal> sig = new ArrayList<>(in.signals());
        sig.sort(Comparator.comparing(Signal::id));
        for (Signal g : sig) {
            List<String> ev = new ArrayList<>(List.of("signal:" + g.id()));
            g.evidence().stream().map(e -> e.reference()).filter(x -> x != null && !x.isBlank()).distinct().sorted().forEach(ev::add);
            out.add(assertion(Type.SIGNAL, "signals", Level.INFERRED, subject, new TreeMap<>(Map.of("signalType", g.type().name(),
                    "severity", g.severity().name(), "detector", g.detectorId(), "confidence", BigDecimal.valueOf(g.confidence()).toPlainString())), List.copyOf(ev)));
        }
        return List.copyOf(out);
    }

    /** A step that could not run (budget, provider failure): a stated limit, never skipped silently. */
    public static Assertion limit(Address subject, String step, String reason) {
        return assertion(Type.STEP_LIMIT, step, Level.OBSERVED, subject.network().id() + ":" + subject.value(),
                Map.of("step", step, "reason", reason), List.of());
    }

    static Assertion assertion(Type t, String step, Level level, String subject, Map<String, String> params, List<String> evidence) {
        Map<String, String> p = new TreeMap<>(params);
        List<String> ev = List.copyOf(new TreeSet<>(evidence));
        StringBuilder key = new StringBuilder(VERSION).append('|').append(t).append('|').append(subject);
        p.forEach((k, v) -> key.append('|').append(k).append('=').append(v));
        ev.forEach(e -> key.append("|ev=").append(e));
        return new Assertion("as_" + sha(key.toString()), t, step, level, subject, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(p)), ev);
    }

    private static String sha(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<AddressLabel> sortedLabels(List<AddressLabel> l) {
        List<AddressLabel> s = new ArrayList<>(l);
        s.sort(Comparator.comparing((AddressLabel x) -> x.category().name()).thenComparing(AddressLabel::source));
        return s;
    }

    /** The trace source seen as a flow store (exposure qualification reads a {@link FlowStore}). */
    private record SourceFlowStore(Trace.Source src) implements FlowStore {
        @Override public boolean add(ValueFlow flow) { throw new UnsupportedOperationException("read only"); }
        @Override public boolean contains(String eventId) { return false; }
        @Override public List<ValueFlow> outgoing(Address a) { return sorted(src.outgoing(a)); }
        @Override public List<ValueFlow> incoming(Address a) { return sorted(src.incoming(a)); }
        private static List<ValueFlow> sorted(List<ValueFlow> l) {
            Map<String, ValueFlow> m = new TreeMap<>();
            for (ValueFlow f : l) m.putIfAbsent(f.eventId(), f);
            List<ValueFlow> out = new ArrayList<>(m.values());
            out.sort(Comparator.comparing(ValueFlow::timestamp).thenComparing(ValueFlow::eventId));
            return out;
        }
    }

    private record SourceLabels(Trace.Source src) implements LabelRepository {
        @Override public void save(AddressLabel label) { throw new UnsupportedOperationException("read only"); }
        @Override public List<AddressLabel> findByAddress(Address address) { return sortedLabels(src.labels(address)); }
        @Override public boolean remove(Address address, io.openchain.intelligence.label.LabelCategory category, String source) {
            throw new UnsupportedOperationException("read only");
        }
        @Override public int size() { return -1; }
    }
}
