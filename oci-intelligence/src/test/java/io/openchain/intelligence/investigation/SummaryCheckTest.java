package io.openchain.intelligence.investigation;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.intelligence.investigation.InvestigationPlan.Assertion;
import io.openchain.intelligence.investigation.InvestigationPlan.Level;
import io.openchain.intelligence.investigation.InvestigationPlan.Type;
import io.openchain.intelligence.investigation.SummaryCheck.Sentence;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** ADR-0022 amendment 5, the test "no claim outside the assertions": the checker refuses every kind of invented claim. */
class SummaryCheckTest {
    static final String SUBJECT = "0x" + "a11ce".repeat(8), LISTED = "0x" + "5a9c0".repeat(8), NEXT = "0x" + "e0e0e".repeat(8),
            USDT = "0xdac17f958d2ee523a2206206994597c13d831ec7";

    static Assertion a(String id, Type t, Level l, Map<String, String> p) {
        return new Assertion(id, t, "step", l, "ethereum:" + SUBJECT, new TreeMap<>(p), List.of());
    }

    final Assertion coverage = a("as_cov", Type.COVERAGE, Level.OBSERVED, Map.of("state", "PARTIAL", "fromBlock", "100", "toBlock", "200",
            "reasons", "address history PENDING"));
    final Assertion exposure = a("as_exp", Type.DIRECT_EXPOSURE, Level.INFERRED, Map.of("counterparty", LISTED, "category", "SANCTIONED",
            "labelSource", "ofac-sdn", "received:" + USDT, "1000000000", "sent:" + USDT, "0", "decimals:" + USDT, "6", "symbol:" + USDT, "USDT"));
    final Assertion trace = a("as_tr", Type.TRACE_ATTRIBUTION, Level.INFERRED, Map.of("direction", "FORWARD", "address", NEXT, "tokenKey", USDT,
            "depth", "1", "low", "600000000/1", "high", "600000000/1", "partial", "false", "method", "PROPORTIONAL", "decimals", "6", "symbol", "USDT"));
    final Assertion label = a("as_lab", Type.LABEL, Level.CONFIRMED, Map.of("source", "ofac-sdn", "category", "SANCTIONED"));
    final Assertion scam = a("as_scam", Type.LABEL, Level.INFERRED, Map.of("source", "community", "category", "SCAM"));
    final List<Assertion> all = List.of(coverage, exposure, trace, label, scam);

    SummaryCheck.Verdict check(Sentence... s) { return SummaryCheck.check(List.of(s), all); }

    @Test void aSummaryThatStaysWithinItsAssertionsIsAcceptedAndCoverageComesFirst() {
        SummaryCheck.Verdict v = check(
                new Sentence("The address received 1000 USDT from " + LISTED + ", listed as sanctioned by ofac-sdn.", List.of("as_exp")),
                new Sentence("About 600 USDT of what it sent reached " + NEXT.substring(0, 8) + "…" + NEXT.substring(NEXT.length() - 4) + ", one hop away.",
                        List.of("as_tr")));
        assertThat(v.reasons()).isEmpty();
        assertThat(v.accepted()).isTrue();
        assertThat(v.sentences().getFirst().assertionIds()).as("partial coverage is stated first").containsExactly("as_cov");
        assertThat(v.sentences().getFirst().text()).isEqualTo(InvestigationReport.text(coverage));
        assertThat(v.sentences()).hasSize(3);
    }

    @Test void aSentenceMustCiteExistingAssertions() {
        assertThat(check(new Sentence("Nothing cited.", List.of())).reasons()).anyMatch(r -> r.contains("cites no assertion"));
        assertThat(check(new Sentence("Invented citation.", List.of("as_nope"))).reasons()).anyMatch(r -> r.contains("unknown assertion as_nope"));
    }

    @Test void anAddressNotInTheCitedAssertionsIsRefused() {
        SummaryCheck.Verdict v = check(new Sentence("Funds reached " + NEXT + ".", List.of("as_exp"))); // NEXT is in as_tr, not cited
        assertThat(v.accepted()).isFalse();
        assertThat(v.reasons()).anyMatch(r -> r.contains(NEXT));
        assertThat(check(new Sentence("Funds reached 0xdeadbeef…9999.", List.of("as_tr"))).accepted()).isFalse();
    }

    @Test void aNumberNotSupportedByTheCitedAssertionsIsRefused() {
        assertThat(check(new Sentence("It received 2000 USDT.", List.of("as_exp"))).accepted()).isFalse();
        assertThat(check(new Sentence("It received 1,000 USDT.", List.of("as_exp"))).accepted()).isTrue();
        assertThat(check(new Sentence("It received 1000.00 USDT.", List.of("as_exp"))).accepted()).isTrue();
        assertThat(check(new Sentence("37 wallets are involved.", List.of("as_exp"))).accepted()).isFalse();
    }

    @Test void confirmedNeedsAConfirmedAssertion() {
        assertThat(check(new Sentence("The exposure is confirmed.", List.of("as_exp"))).reasons()).anyMatch(r -> r.contains("confirmed"));
        assertThat(check(new Sentence("The listing by ofac-sdn is confirmed by its source.", List.of("as_lab"))).accepted()).isTrue();
    }

    @Test void identityOwnershipIntentAndGuiltAreNeverStated() {
        for (String s : List.of("The wallet is owned by the same person as " + LISTED + ".", "This is money laundering.",
                "The address belongs to a criminal group.", "The subject controls " + NEXT + ".", "It is guilty of fraud.")) {
            assertThat(check(new Sentence(s, List.of("as_exp", "as_tr"))).accepted()).as(s).isFalse();
        }
        // a category word is allowed when a cited assertion carries that category
        assertThat(check(new Sentence("A community source lists it as a scam.", List.of("as_scam"))).accepted()).isTrue();
        assertThat(check(new Sentence("It is a scam.", List.of("as_exp"))).accepted()).isFalse();
    }

    @Test void completeCoverageImposesNothingAndLimitsAreEnforced() {
        Assertion complete = a("as_cov", Type.COVERAGE, Level.OBSERVED, Map.of("state", "COMPLETE", "fromBlock", "1", "toBlock", "2"));
        SummaryCheck.Verdict v = SummaryCheck.check(List.of(new Sentence("It received 1000 USDT.", List.of("as_exp"))), List.of(complete, exposure));
        assertThat(v.accepted()).isTrue();
        assertThat(v.sentences()).hasSize(1);
        assertThat(check(new Sentence("x".repeat(SummaryCheck.MAX_SENTENCE + 1), List.of("as_exp"))).accepted()).isFalse();
        assertThat(SummaryCheck.check(List.of(), all).reasons()).anyMatch(r -> r.contains("empty"));
    }
}
