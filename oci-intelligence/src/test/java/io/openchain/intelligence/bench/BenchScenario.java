package io.openchain.intelligence.bench;

import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One evaluation scenario of the detection bench. SYNTHETIC DATA ONLY: fictional addresses and labels.
 *
 * <p>Expectations, per subject address:
 * <ul>
 *   <li>{@code expect}: the signal type must be produced for that address (true positive, else false negative);</li>
 *   <li>{@code forbid}: the signal type must not be produced for that address (false positive if it is);</li>
 *   <li>{@code missing}: a behaviour no detector can recognise yet (counted as not detected, so coverage gaps stay visible);</li>
 *   <li>{@code tolerate}: a <b>legitimate statistical anomaly</b> under a named business rule. It is neither a true nor a
 *       false positive of suspicious-activity detection, provided it stays an ANOMALY at most at {@code maxSeverity};
 *       otherwise it counts as a false positive. Reported apart, with its weight in the risk score.</li>
 * </ul>
 * Signals not covered by an expectation are counted as "unasserted" (noise indicator, not scored).
 */
public final class BenchScenario {
    public enum Kind { SUSPICIOUS, BENIGN }

    public enum Mode { EXPECT, FORBID, MISSING, TOLERATE }

    public record Expectation(Address subject, String subjectName, SignalType type, String capability, Mode mode,
                              io.openchain.core.detection.Severity maxSeverity, String rule) {
        public String key() { return type != null ? type.name() : "MISSING:" + capability; }
        public boolean expected() { return mode == Mode.EXPECT || mode == Mode.MISSING; }
    }

    public final String id;
    public final Kind kind;
    public final String description;
    public final List<AddressLabel> labels = new ArrayList<>();
    public final List<ValueFlow> flows = new ArrayList<>();
    public final List<Expectation> expectations = new ArrayList<>();
    public final Map<String, String> names = new TreeMap<>();

    /**
     * Timeline of a scenario that contains chain reorganisations: transfers added, retracted (their block left the
     * canonical chain) or re-included (the same transaction in another canonical block), in the declared order.
     * Scenarios with a timeline are evaluated on the <b>final</b> state of the signal store, not on every signal ever
     * produced: a signal whose evidence disappeared must be gone, or recomputed from the remaining evidence.
     */
    public enum StepKind { ADD, RETRACT, REINCLUDE }
    public record Step(StepKind kind, ValueFlow flow) {}
    public final List<Step> timeline = new ArrayList<>();

    public boolean hasTimeline() { return timeline.stream().anyMatch(st -> st.kind() != StepKind.ADD); }

    public BenchScenario(String id, Kind kind, String description) {
        this.id = id;
        this.kind = kind;
        this.description = description;
    }

    public BenchScenario name(Address a, String name) { names.put(a.value(), name); return this; }
    public BenchScenario label(AddressLabel l) { labels.add(l); return this; }
    public BenchScenario flow(ValueFlow f) { flows.add(f); timeline.add(new Step(StepKind.ADD, f)); return this; }
    public BenchScenario retract(ValueFlow f) { timeline.add(new Step(StepKind.RETRACT, f)); return this; }
    public BenchScenario reinclude(ValueFlow f) { timeline.add(new Step(StepKind.REINCLUDE, f)); return this; }
    public BenchScenario expect(Address a, SignalType t) { expectations.add(new Expectation(a, nameOf(a), t, null, Mode.EXPECT, null, null)); return this; }
    public BenchScenario forbid(Address a, SignalType t) { expectations.add(new Expectation(a, nameOf(a), t, null, Mode.FORBID, null, null)); return this; }
    public BenchScenario missing(Address a, String capability) { expectations.add(new Expectation(a, nameOf(a), null, capability, Mode.MISSING, null, null)); return this; }
    public BenchScenario tolerate(Address a, SignalType t, io.openchain.core.detection.Severity maxSeverity, String rule) {
        expectations.add(new Expectation(a, nameOf(a), t, null, Mode.TOLERATE, maxSeverity, rule)); return this;
    }

    public String nameOf(Address a) { return names.getOrDefault(a.value(), a.value()); }
}
