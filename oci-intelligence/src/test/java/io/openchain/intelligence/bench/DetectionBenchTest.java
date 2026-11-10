package io.openchain.intelligence.bench;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Signal;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.risk.ScoringConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * Detection bench (P0): replays every scenario of {@link BenchScenarios} through the production pipeline with the
 * default configuration and reports, per signal type, true positives, false positives and false negatives.
 * The report is written to {@code target/detection-bench.md}. Only malformed scenarios and detector errors fail the
 * build: the metrics are a measurement, compared from one change to the next.
 */
class DetectionBenchTest {

    record Outcome(String scenario, BenchScenario.Kind kind, BenchScenario.Expectation e, boolean fired) {}

    /** A tolerated anomaly as observed: whether it respected its rule, and its weight in the subject's risk score. */
    record Tolerated(String scenario, BenchScenario.Expectation e, String severity, String evidenceLevel, boolean withinRule,
                     double points, int score, String level) {}

    @Test void runBench() throws IOException {
        List<Outcome> outcomes = new ArrayList<>();
        Map<String, Integer> unasserted = new TreeMap<>();
        Map<String, List<String>> firedPerScenario = new TreeMap<>();
        Map<String, String> errors = new TreeMap<>();
        List<Tolerated> tolerated = new ArrayList<>();
        for (BenchScenario s : BenchScenarios.all()) {
            assertThat(s.flows).as(s.id + " has flows").isNotEmpty();
            assertThat(s.expectations).as(s.id + " has expectations").isNotEmpty();
            List<ValueFlow> ordered = new ArrayList<>(s.flows);
            ordered.sort(Comparator.comparing(ValueFlow::timestamp));
            var end = ordered.getLast().timestamp().plusSeconds(60);
            IntelligencePipeline p = IntelligencePipeline.inMemory(DetectionConfig.defaults(), ScoringConfig.defaults(), Clock.fixed(end, ZoneOffset.UTC));
            s.labels.forEach(p.labels()::save);
            List<Signal> signals = new ArrayList<>();
            for (ValueFlow f : ordered) {
                var r = p.onFlow(f);
                signals.addAll(r.newSignals());
                r.detectorErrors().forEach((k, v) -> errors.put(s.id + "/" + k, v));
            }
            for (BenchScenario.Expectation e : s.expectations) {
                List<Signal> matching = e.type() == null ? List.of()
                        : signals.stream().filter(x -> x.address().equals(e.subject()) && x.type() == e.type()).toList();
                if (e.mode() == BenchScenario.Mode.TOLERATE) {
                    // legitimate only as an ANOMALY within the severity cap; otherwise a false positive of the detection
                    boolean within = matching.stream().allMatch(x -> x.evidenceLevel() == Signal.EvidenceLevel.ANOMALY
                            && x.severity().compareTo(e.maxSeverity()) <= 0);
                    if (!within) outcomes.add(new Outcome(s.id, s.kind, e, true));
                    var score = p.scoring().latest(e.subject());
                    double pts = score.map(r -> r.contributions().stream().filter(c -> c.factor().equals("signal:" + e.type()))
                            .mapToDouble(c -> c.points()).sum()).orElse(0.0);
                    for (Signal x : matching)
                        tolerated.add(new Tolerated(s.id, e, x.severity().name(), x.evidenceLevel().name(), within, pts,
                                score.map(r -> r.score()).orElse(0), score.map(r -> r.level().name()).orElse("none")));
                    continue;
                }
                outcomes.add(new Outcome(s.id, s.kind, e, !matching.isEmpty()));
            }
            List<String> fired = new ArrayList<>();
            for (Signal x : signals) {
                String k = s.nameOf(x.address()) + ":" + x.type();
                fired.add(k + "/" + x.severity());
                boolean asserted = s.expectations.stream().anyMatch(e -> e.type() == x.type() && e.subject().equals(x.address()));
                if (!asserted) unasserted.merge(s.id, 1, Integer::sum);
            }
            firedPerScenario.put(s.id, fired);
        }
        assertThat(errors).as("detector errors").isEmpty();
        String report = report(outcomes, unasserted, firedPerScenario, tolerated);
        Path out = Path.of("target", "detection-bench.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        System.out.println(report);
    }

    static String report(List<Outcome> outcomes, Map<String, Integer> unasserted, Map<String, List<String>> fired, List<Tolerated> tolerated) {
        StringBuilder b = new StringBuilder("# Detection bench\n\nSynthetic scenarios only (see BenchScenarios). Default detection and scoring configuration.\n\n");
        // per signal type / capability
        Map<String, int[]> per = new TreeMap<>(); // tp, fp, fn, tn
        for (Outcome o : outcomes) {
            int[] c = per.computeIfAbsent(o.e().key(), k -> new int[4]);
            if (o.e().expected()) { if (o.fired()) c[0]++; else c[2]++; } else { if (o.fired()) c[1]++; else c[3]++; }
        }
        b.append("## Per signal type\n\n| Signal type / capability | TP | FP | FN | TN | Precision | Recall |\n|---|---:|---:|---:|---:|---:|---:|\n");
        int tp = 0, fp = 0, fn = 0, tn = 0;
        for (var e : per.entrySet()) {
            int[] c = e.getValue();
            tp += c[0]; fp += c[1]; fn += c[2]; tn += c[3];
            b.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %s | %s |%n", e.getKey(), c[0], c[1], c[2], c[3], ratio(c[0], c[0] + c[1]), ratio(c[0], c[0] + c[2])));
        }
        b.append(String.format(Locale.ROOT, "| **All** | **%d** | **%d** | **%d** | **%d** | **%s** | **%s** |%n%n", tp, fp, fn, tn, ratio(tp, tp + fp), ratio(tp, tp + fn)));
        long missing = outcomes.stream().filter(o -> o.e().type() == null).count();
        b.append("Missing capabilities (no detector exists): ").append(missing).append(" expectation(s), counted as FN above.\n\n");
        b.append("## Per scenario\n\n| Scenario | Kind | Expectations met | Failed expectations | Unasserted signals |\n|---|---|---:|---|---:|\n");
        Map<String, List<Outcome>> byScenario = new TreeMap<>();
        for (Outcome o : outcomes) byScenario.computeIfAbsent(o.scenario(), k -> new ArrayList<>()).add(o);
        for (var e : byScenario.entrySet()) {
            List<Outcome> l = e.getValue();
            long ok = l.stream().filter(o -> o.fired() == o.e().expected()).count();
            List<String> failed = l.stream().filter(o -> o.fired() != o.e().expected())
                    .map(o -> (o.e().expected() ? "missed " : "false positive ") + o.e().subjectName() + ":" + o.e().key()).toList();
            b.append(String.format(Locale.ROOT, "| %s | %s | %d/%d | %s | %d |%n", e.getKey(), l.getFirst().kind(), ok, l.size(),
                    failed.isEmpty() ? "–" : String.join("; ", failed), unasserted.getOrDefault(e.getKey(), 0)));
        }
        b.append("\n## Tolerated anomalies (business rule, not scored as suspicious-activity detection)\n\n");
        if (tolerated.isEmpty()) b.append("None.\n");
        else {
            b.append("| Scenario | Subject | Signal | Severity | Evidence level | Within rule | Points in score | Subject score | Rule |\n|---|---|---|---|---|---|---:|---:|---|\n");
            for (Tolerated t : tolerated)
                b.append(String.format(Locale.ROOT, "| %s | %s | %s | %s | %s | %s | %.1f | %d %s | %s |%n", t.scenario(), t.e().subjectName(), t.e().type(),
                        t.severity(), t.evidenceLevel(), t.withinRule() ? "yes" : "NO (counted as FP)", t.points(), t.score(), t.level(), t.e().rule()));
        }
        b.append("\n## Signals produced\n\n");
        fired.forEach((k, v) -> b.append("- ").append(k).append(": ").append(v.isEmpty() ? "none" : String.join(", ", v)).append('\n'));
        return b.toString();
    }

    static String ratio(int a, int b) { return b == 0 ? "–" : String.format(Locale.ROOT, "%.2f", a / (double) b); }
}
