package io.openchain.intelligence.investigation;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.relations.WalletRelations;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceEngineTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** ADR-0022 amendment 2: no statement without provenance; "none" only with complete coverage. */
class InvestigationReportTest {
    final TraceEngineTest.Src src = new TraceEngineTest.Src();
    final Address me = a("a11ce"), s = a("5a9c"), b = a("b0b"), f = a("f00d"), x = a("ee");
    final Map<String, BigDecimal> dust = Map.of("USDT", new BigDecimal("10"));

    List<InvestigationPlan.Assertion> run(String coverage) {
        var ex = DetectionConfig.defaults().exposure();
        ex = new DetectionConfig.Exposure(ex.maxHops(), ex.hopDecay(), ex.minConfidence(), ex.maxPaths(), ex.maxVisitedFlows(), ex.stopAt(), dust);
        return InvestigationPlan.run(new InvestigationPlan.Inputs(me, new InvestigationPlan.Coverage(coverage, 100L, 200L, List.of()), List.of(), ex,
                WalletRelations.Config.defaults(dust), new Trace.Limits(3, 500, 20_000)), src);
    }

    @Test void everySentenceCitesAnAssertionAndEveryAssertionIsReported() {
        src.label(s, LabelCategory.SANCTIONED);
        src.flow(s, me, 1_000, 1); src.flow(s, me, 1, 2); src.flow(me, x, 600, 3); src.flow(f, me, 500, 0); src.flow(f, b, 500, 1);
        List<InvestigationPlan.Assertion> as = run("PARTIAL");
        var report = InvestigationReport.build("ethereum:" + me.value(), as);
        Set<String> ids = as.stream().map(InvestigationPlan.Assertion::id).collect(Collectors.toSet());
        List<InvestigationReport.Sentence> all = report.sections().stream().flatMap(sec -> sec.sentences().stream()).toList();
        assertThat(all).allMatch(t -> ids.contains(t.assertionId()));
        assertThat(all.stream().map(InvestigationReport.Sentence::assertionId).collect(Collectors.toSet())).containsAll(ids);
        assertThat(report.sections().getFirst().title()).isEqualTo("Coverage and limits");
        assertThat(report.sections().getFirst().sentences().getFirst().text()).startsWith("Data covered: partial");
        assertThat(all).anyMatch(t -> t.text().startsWith("Direct transfers with " + s.value()) && t.text().contains("received 1000 USDT"));
        assertThat(all).anyMatch(t -> t.text().startsWith("Ignored 1 USDT from " + s.value()));
        assertThat(all).anyMatch(t -> t.text().startsWith(b.value() + " may be related") && t.text().contains("Not proof of common ownership"));
        assertThat(all).noneMatch(t -> t.text().toLowerCase().contains("same owner") || t.text().toLowerCase().contains("controlled by"));
        assertThat(InvestigationReport.build("ethereum:" + me.value(), as)).isEqualTo(report);
    }

    @Test void noneIsOnlySaidWithCompleteCoverage() {
        src.flow(me, x, 600, 3);
        String partial = sentencesOf(run("PARTIAL"), "Exposure to listed addresses");
        assertThat(partial).contains("was found in the data covered; this is not proof that none exists").doesNotContain("in blocks");
        String complete = sentencesOf(run("COMPLETE"), "Exposure to listed addresses");
        assertThat(complete).contains("No direct exposure to a listed address in blocks 100 to 200.");
    }

    @Test void exactAmountsAreShownInTokenUnits() {
        assertThat(InvestigationReport.units("1500000", "6")).isEqualTo("1.5");
        assertThat(InvestigationReport.fraction("3000000000/100", "6")).isEqualTo("30");
        assertThat(InvestigationReport.fraction("1/3", "0")).isEqualTo("0.333333");
    }

    static String sentencesOf(List<InvestigationPlan.Assertion> as, String section) {
        return InvestigationReport.build("s", as).sections().stream().filter(x -> x.title().equals(section)).findFirst().orElseThrow()
                .sentences().stream().map(InvestigationReport.Sentence::text).collect(Collectors.joining("\n"));
    }
}
