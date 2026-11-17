package io.openchain.intelligence.investigation;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.investigation.InvestigationPlan.Level;
import io.openchain.intelligence.investigation.InvestigationPlan.Type;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.relations.WalletRelations;
import io.openchain.intelligence.trace.FrozenSource;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceEngineTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-0022 amendment 2: the plan is deterministic, replayable on frozen evidence, and never over-claims. */
class InvestigationPlanTest {
    final TraceEngineTest.Src src = new TraceEngineTest.Src();
    final Address me = a("a11ce"), s = a("5a9c"), b = a("b0b"), f = a("f00d"), x = a("ee");
    final Map<String, BigDecimal> dust = Map.of("USDT", new BigDecimal("10"));

    InvestigationPlan.Inputs inputs() {
        var ex = DetectionConfig.defaults().exposure();
        ex = new DetectionConfig.Exposure(ex.maxHops(), ex.hopDecay(), ex.minConfidence(), ex.maxPaths(), ex.maxVisitedFlows(), ex.stopAt(), dust);
        return new InvestigationPlan.Inputs(me, new InvestigationPlan.Coverage("PARTIAL", 100L, 200L, List.of("address history PENDING")),
                List.of(), ex, WalletRelations.Config.defaults(dust), new Trace.Limits(3, 500, 20_000));
    }

    void world() {
        src.label(s, LabelCategory.SANCTIONED);  // CONFIRMED_BY_SOURCE in the test fixture
        src.flow(s, me, 1_000, 1);
        src.flow(s, me, 1, 2);                   // dust: ignored
        src.flow(me, x, 600, 3);
        src.flow(f, me, 500, 0); src.flow(f, b, 500, 1); // common funder
    }

    @Test void theSameInputsGiveTheSameAssertionsAndIds() {
        world();
        List<InvestigationPlan.Assertion> first = InvestigationPlan.run(inputs(), src);
        java.util.Collections.reverse(src.flows);
        assertThat(InvestigationPlan.run(inputs(), src)).isEqualTo(first);
        assertThat(first).extracting(InvestigationPlan.Assertion::id).doesNotHaveDuplicates().allMatch(id -> id.startsWith("as_"));
    }

    @Test void replayOnTheFrozenEvidenceGivesTheSameAssertions() {
        world();
        var recorder = FrozenSource.recording(src);
        List<InvestigationPlan.Assertion> live = InvestigationPlan.run(inputs(), recorder);
        FrozenSource.Snapshot frozen = recorder.snapshot();
        src.flows.clear(); // the live data changed completely
        assertThat(InvestigationPlan.run(inputs(), new FrozenSource(frozen))).isEqualTo(live);
    }

    @Test void everyStepIsRepresentedInOrderAndNothingIsOverClaimed() {
        world();
        List<InvestigationPlan.Assertion> as = InvestigationPlan.run(inputs(), src);
        assertThat(as.getFirst().type()).isEqualTo(Type.COVERAGE);
        assertThat(as.getFirst().params()).containsEntry("state", "PARTIAL");
        assertThat(as).filteredOn(x -> x.type() == Type.DIRECT_EXPOSURE).singleElement().satisfies(d -> {
            assertThat(d.level()).as("an exposure built on a confirmed label is still an inference").isEqualTo(Level.INFERRED);
            assertThat(d.params()).containsEntry("counterparty", s.value()).containsEntry("category", "SANCTIONED");
            assertThat(d.evidence()).hasSize(1);
        });
        assertThat(as).filteredOn(x -> x.type() == Type.IGNORED_TRANSFER).singleElement()
                .satisfies(i -> assertThat(i.params()).containsEntry("reason", "below_dust_threshold"));
        assertThat(as).filteredOn(x -> x.type() == Type.TRACE_ATTRIBUTION).isNotEmpty().allMatch(x -> x.level() == Level.INFERRED);
        assertThat(as).filteredOn(x -> x.type() == Type.RELATION).singleElement()
                .satisfies(r -> assertThat(r.params()).containsEntry("other", b.value()).containsEntry("relation", "COMMON_FUNDER"));
        assertThat(as).filteredOn(x -> x.level() == Level.CONFIRMED).isEmpty(); // the subject itself carries no confirmed label
        // the step order of the plan is kept
        List<String> steps = as.stream().map(InvestigationPlan.Assertion::step).distinct().toList();
        assertThat(InvestigationPlan.STEPS).containsSubsequence(steps);
    }

    @Test void aConfirmedLabelOfTheSubjectIsConfirmedAndACommunityOneIsNot() {
        src.label(me, LabelCategory.SANCTIONED);
        src.label(me, LabelCategory.FLAGGED_COMMUNITY);
        List<InvestigationPlan.Assertion> as = InvestigationPlan.run(inputs(), src);
        assertThat(as).filteredOn(x -> x.type() == Type.LABEL).extracting(x -> x.params().get("category") + "=" + x.level())
                .containsExactly("FLAGGED_COMMUNITY=INFERRED", "SANCTIONED=CONFIRMED");
    }

    @Test void aStepThatCouldNotRunIsAStatedLimit() {
        InvestigationPlan.Assertion l = InvestigationPlan.limit(me, "traces", "budget exhausted");
        assertThat(l.type()).isEqualTo(Type.STEP_LIMIT);
        assertThat(l.level()).isEqualTo(Level.OBSERVED);
        assertThat(l.params()).containsEntry("reason", "budget exhausted");
    }
}
