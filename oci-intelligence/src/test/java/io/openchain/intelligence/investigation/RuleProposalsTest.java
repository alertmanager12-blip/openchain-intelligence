package io.openchain.intelligence.investigation;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.investigation.RuleProposals.Template;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.relations.WalletRelations;
import io.openchain.intelligence.trace.Trace;
import io.openchain.intelligence.trace.TraceEngineTest;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR-0022 amendment 4: deterministic drafts, always disabled, citing their assertions; a rejected assertion gives none. */
public class RuleProposalsTest {
    final TraceEngineTest.Src src = new TraceEngineTest.Src();
    final Address me = a("a11ce"), s = a("5a9c"), b = a("b0b"), f = a("f00d"), x = a("ee");

    InvestigationPlan.Inputs inputs() {
        Map<String, BigDecimal> dust = Map.of("USDT", new BigDecimal("10"));
        var ex = DetectionConfig.defaults().exposure();
        ex = new DetectionConfig.Exposure(ex.maxHops(), ex.hopDecay(), ex.minConfidence(), ex.maxPaths(), ex.maxVisitedFlows(), ex.stopAt(), dust);
        return new InvestigationPlan.Inputs(me, new InvestigationPlan.Coverage("PARTIAL", 100L, 200L, List.of()), List.of(), ex,
                WalletRelations.Config.defaults(dust), new Trace.Limits(3, 500, 20_000));
    }

    /** Exposure to a sanctioned sender, value sent on to x, a common funder f, and a dispersion by the subject. */
    public void world() {
        src.label(s, LabelCategory.SANCTIONED);
        src.flow(s, me, 1_000, 1);
        src.flow(me, x, 600, 3);
        src.flow(f, me, 500, 0); src.flow(f, b, 500, 1);
        for (int i = 0; i < 24; i++) src.flow(me, a("d1" + i), 10, 4);
    }

    RuleProposals.Result propose(Map<String, String> decisions) {
        List<InvestigationPlan.Assertion> as = InvestigationPlan.run(inputs(), src);
        return RuleProposals.propose("inv-1", 2, me.network().id(), me.value(), as, decisions, src.flows);
    }

    @Test void everyTemplateGivesADisabledDraftCitingItsAssertions() {
        world();
        RuleProposals.Result r = propose(Map.of());
        assertThat(r.version()).isEqualTo("proposal@1");
        assertThat(r.proposals()).extracting(RuleProposals.Proposal::template)
                .contains(Template.NEXT_HOP, Template.COMMON_FUNDER, Template.SUBJECT_DISPERSION, Template.LISTED_COUNTERPARTY);
        assertThat(r.proposals()).allSatisfy(p -> {
            assertThat(p.yaml()).contains("enabled: false\n").contains("author: \"investigation:inv-1@v2\"")
                    .contains("tags: [proposed, investigation, " + p.template().slug + "]").contains("expect: MATCH").contains("expect: NO_MATCH");
            assertThat(p.ruleId()).matches("^inv-[0-9a-f]{12}-" + p.template().slug + "$");
            for (String id : p.assertionIds()) assertThat(p.yaml()).contains(id);
        });
        assertThat(r.proposals()).extracting(RuleProposals.Proposal::ruleId).doesNotHaveDuplicates();
        RuleProposals.Proposal hop = r.proposals().stream().filter(p -> p.template() == Template.NEXT_HOP && p.yaml().contains(x.value()))
                .findFirst().orElseThrow();
        assertThat(hop.yaml()).contains("field: transaction.from").contains(x.value());
    }

    @Test void theSameAssertionsGiveTheSameYamlByteForByte() {
        world();
        RuleProposals.Result first = propose(Map.of());
        java.util.Collections.reverse(src.flows);
        assertThat(propose(Map.of())).isEqualTo(first);
    }

    @Test void aRejectedAssertionGivesNoDraftAndIsListedAsSkipped() {
        world();
        RuleProposals.Result all = propose(Map.of());
        RuleProposals.Proposal exposure = all.proposals().stream().filter(p -> p.template() == Template.LISTED_COUNTERPARTY).findFirst().orElseThrow();
        String id = exposure.assertionIds().getFirst();
        Map<String, String> decisions = new HashMap<>(Map.of(id, "REJECTED"));
        RuleProposals.Result after = propose(decisions);
        assertThat(after.proposals()).extracting(RuleProposals.Proposal::template).doesNotContain(Template.LISTED_COUNTERPARTY);
        assertThat(after.skipped()).contains(new RuleProposals.Skipped(id, "rejected by a review"));
        // an accepted assertion is still proposed, with its decision shown
        RuleProposals.Result accepted = propose(Map.of(id, "ACCEPTED"));
        assertThat(accepted.proposals()).filteredOn(p -> p.template() == Template.LISTED_COUNTERPARTY).singleElement()
                .satisfies(p -> assertThat(p.reviews()).containsEntry(id, "ACCEPTED"));
    }

    /** 24 small recipients of a dispersion would give 24 next-hop drafts: only the 5 that received the most value are kept. */
    @Test void nextHopDraftsAreKeptForTheLargestAttributionsOnly() {
        world();
        RuleProposals.Result r = propose(Map.of());
        assertThat(r.proposals()).filteredOn(p -> p.template() == Template.NEXT_HOP).hasSize(RuleProposals.MAX_NEXT_HOPS)
                .anySatisfy(p -> assertThat(p.yaml()).contains(x.value())); // 600 USDT, the largest
        assertThat(r.skipped()).filteredOn(k -> k.reason().startsWith("not among the 5")).hasSize(25 - RuleProposals.MAX_NEXT_HOPS);
    }

    @Test void yamlStringsAreQuotedAndEscaped() {
        assertThat(RuleProposals.q("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\c\\nd\"");
    }
}
