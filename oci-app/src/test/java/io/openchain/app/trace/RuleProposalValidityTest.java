package io.openchain.app.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.testing.EmbeddedTestReport;
import io.openchain.alerting.testing.RuleTester;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.investigation.InvestigationPlan;
import io.openchain.intelligence.investigation.RuleProposals;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.relations.WalletRelations;
import io.openchain.intelligence.trace.Trace;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * ADR-0022 amendment 4, acceptance "valid draft, tested": every draft of every template, on Ethereum and on TRON, is valid
 * for the real rule loader, is disabled, and passes the examples and tests it carries.
 */
class RuleProposalValidityTest {
    static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");

    enum Chain { ETHEREUM, TRON }

    static final class World implements Trace.Source {
        final Network net;
        final Token usdt;
        final List<ValueFlow> flows = new ArrayList<>();
        final Map<Address, List<AddressLabel>> labels = new java.util.HashMap<>();
        int seq;

        World(Chain c) {
            net = c == Chain.ETHEREUM ? Network.ETHEREUM : Network.TRON;
            usdt = c == Chain.ETHEREUM ? new Token(net, "0xdAC17F958D2ee523a2206206994597C13D831ec7".toLowerCase(), "USDT", 6)
                    : new Token(net, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
        }

        Address a(String tag) {
            return net == Network.ETHEREUM ? new Address(net, "0x" + (tag + "0".repeat(40)).substring(0, 40))
                    : new Address(net, ("TPrp" + tag + "1".repeat(34)).substring(0, 34));
        }

        void flow(Address from, Address to, long usdtAmount, int minute) {
            String tx = "%064x".formatted(++seq);
            flows.add(new ValueFlow(net.id() + ":" + tx + ":0", net, tx, T0.plusSeconds(60L * minute), from, to, usdt,
                    BigInteger.valueOf(usdtAmount).multiply(BigInteger.TEN.pow(6))));
        }

        @Override public List<ValueFlow> outgoing(Address x) { return flows.stream().filter(f -> f.from().equals(x)).toList(); }
        @Override public List<ValueFlow> incoming(Address x) { return flows.stream().filter(f -> f.to().equals(x)).toList(); }
        @Override public boolean complete(Address x, Trace.Window w) { return true; }
        @Override public Optional<BigInteger> openingBalance(Address x, String k, Trace.Window w) { return Optional.of(BigInteger.ZERO); }
        @Override public List<AddressLabel> labels(Address x) { return labels.getOrDefault(x, List.of()); }
    }

    @ParameterizedTest @EnumSource(Chain.class)
    void everyDraftIsValidDisabledAndPassesItsOwnExamplesAndTests(Chain chain) {
        World w = new World(chain);
        Address me = w.a("a11ce"), s = w.a("5a9c"), b = w.a("b0b"), f = w.a("f00d"), x = w.a("ee");
        w.labels.put(s, List.of(new AddressLabel(s, LabelCategory.SANCTIONED, "test", "test-list", 1.0, T0, EvidenceLevel.CONFIRMED_BY_SOURCE, "test://s")));
        w.flow(s, me, 1_000, 1);
        w.flow(me, x, 600, 3);
        w.flow(f, me, 500, 0);
        w.flow(f, b, 500, 1);
        for (int i = 0; i < 24; i++) w.flow(me, w.a("d1" + i), 10, 4);

        Map<String, BigDecimal> dust = Map.of("USDT", new BigDecimal("1"));
        var ex = DetectionConfig.defaults().exposure();
        ex = new DetectionConfig.Exposure(ex.maxHops(), ex.hopDecay(), ex.minConfidence(), ex.maxPaths(), ex.maxVisitedFlows(), ex.stopAt(), dust);
        var in = new InvestigationPlan.Inputs(me, new InvestigationPlan.Coverage("PARTIAL", 1L, 2L, List.of()), List.of(), ex,
                WalletRelations.Config.defaults(dust), new Trace.Limits(3, 500, 20_000));
        RuleProposals.Result r = RuleProposals.propose("inv-x", 1, w.net.id(), me.value(), InvestigationPlan.run(in, w), Map.of(), w.flows);

        assertThat(r.proposals()).extracting(RuleProposals.Proposal::template).containsAll(List.of(RuleProposals.Template.values()));
        RuleLoader loader = new RuleLoader();
        RuleTester tester = new RuleTester(loader);
        for (RuleProposals.Proposal p : r.proposals()) {
            RuleLoader.LoadResult lr = loader.tryLoad(p.yaml(), Format.YAML);
            assertThat(lr.errors()).as(p.ruleId() + "\n" + p.yaml()).isEmpty();
            assertThat(lr.rule().rule().enabled()).as(p.ruleId()).isFalse();
            EmbeddedTestReport t = tester.runEmbeddedTests(lr.rule());
            assertThat(t.results()).as(p.ruleId()).isNotEmpty();
            assertThat(t.failures()).as(p.ruleId() + "\n" + p.yaml()).isEmpty();
        }
    }
}
