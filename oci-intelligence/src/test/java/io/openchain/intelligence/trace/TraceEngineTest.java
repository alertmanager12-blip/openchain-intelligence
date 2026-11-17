package io.openchain.intelligence.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.trace.Trace.Direction;
import io.openchain.intelligence.trace.Trace.Method;
import io.openchain.intelligence.trace.Trace.StopReason;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Fund Flow Trace P0-D, first part: scenarios 1, 2, 5, 8 to 13 of docs/FUND_FLOW_TRACE_P0_D_ENGINE.md § 5, plus
 * determinism and limits. Amounts in whole units of a 0-decimal test token, so that raw = human.
 */
public class TraceEngineTest {
    static final Network NET = Network.ETHEREUM;
    static final Token USD = new Token(NET, "0xusd0000000000000000000000000000000000001", "USDT", 0);
    static final Token ETH = Token.nativeOf(NET, "ETH", 0);
    static final Token FAKE = new Token(NET, "0xfake000000000000000000000000000000000001", Token.UNKNOWN_SYMBOL, 0);
    static final String K = Trace.tokenKey(USD);
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    public static Address a(String s) { return new Address(NET, "0x" + s); }

    /** In-memory source: flows, coverage, opening balances (default 0, i.e. known empty), labels. */
    public static final class Src implements Trace.Source {
        public final List<ValueFlow> flows = new ArrayList<>();
        public final Set<Address> incomplete = new HashSet<>();
        public final Map<Address, Optional<BigInteger>> opening = new HashMap<>();
        final Map<Address, List<AddressLabel>> labels = new HashMap<>();
        int seq;

        public ValueFlow flow(Address from, Address to, long amount, int t) { return flow(from, to, amount, USD, t); }
        public ValueFlow flow(Address from, Address to, long amount, Token tok, int t) {
            String tx = "0x%064x".formatted(++seq);
            ValueFlow f = new ValueFlow(NET.id() + ":" + tx + ":0", NET, tx, T0.plusSeconds(60L * t), from, to, tok, BigInteger.valueOf(amount));
            flows.add(f);
            return f;
        }
        @Override public List<ValueFlow> outgoing(Address x) { return flows.stream().filter(f -> f.from().equals(x)).toList(); }
        @Override public List<ValueFlow> incoming(Address x) { return flows.stream().filter(f -> f.to().equals(x)).toList(); }
        @Override public boolean complete(Address x, Trace.Window w) { return !incomplete.contains(x); }
        @Override public Optional<BigInteger> openingBalance(Address x, String k, Trace.Window w) {
            return opening.getOrDefault(x, Optional.of(BigInteger.ZERO));
        }
        @Override public List<AddressLabel> labels(Address x) { return labels.getOrDefault(x, List.of()); }
        public void label(Address x, LabelCategory c) {
            labels.computeIfAbsent(x, k -> new ArrayList<>()).add(new AddressLabel(x, c, "test " + c, "test-list", 1.0, T0,
                    c == LabelCategory.SANCTIONED ? EvidenceLevel.CONFIRMED_BY_SOURCE : EvidenceLevel.RISK_SIGNAL, "test://" + x.value()));
        }
    }

    final Src src = new Src();
    final Address A = a("a"), B = a("b"), C = a("c"), D = a("d"), E = a("e"), S = a("5"), S1 = a("51"), S2 = a("52"), X = a("ee"),
            Y = a("ff"), Z = a("2"), P = a("99");

    public static Trace.Attribution at(Trace.Result r, Address x) {
        return r.attributions().stream().filter(t -> t.address().equals(x)).findFirst().orElse(null);
    }

    static boolean stopped(Trace.Result r, Address x, StopReason why) {
        return r.stops().stream().anyMatch(s -> s.address().equals(x) && s.reason() == why);
    }

    Trace.Result fwd(Address seed, int depth, Method m) {
        return TraceEngine.run(Trace.Request.of(seed, Direction.FORWARD).withMethod(m).withLimits(new Trace.Limits(depth, 500, 20_000)), src);
    }

    Trace.Result back(Address seed, int depth) {
        return TraceEngine.run(Trace.Request.of(seed, Direction.BACKWARD).withLimits(new Trace.Limits(depth, 500, 20_000))
                .withDust(Map.of("USDT", new BigDecimal("10"))), src);
    }

    /** 1: A -> B -> C, both methods, two pieces of evidence, complete. */
    @Test void s1SimplePath() {
        ValueFlow ab = src.flow(A, B, 100, 1), bc = src.flow(B, C, 100, 2);
        for (Method m : Method.values()) {
            Trace.Result r = fwd(A, 2, m);
            assertThat(r.status()).as(m.name()).isEqualTo(Trace.Status.COMPLETE);
            assertThat(at(r, C).high()).isEqualTo(Fraction.of(100));
            assertThat(at(r, C).low()).isEqualTo(Fraction.of(100));
            assertThat(at(r, C).depth()).isEqualTo(2);
            assertThat(r.edges()).extracting(Trace.Edge::eventId).containsExactly(ab.eventId(), bc.eventId());
        }
    }

    /** 2: the depth limit stops at D, E is not reached, and the result says so. */
    @Test void s2DepthLimit() {
        src.flow(A, B, 100, 1); src.flow(B, C, 100, 2); src.flow(C, D, 100, 3); src.flow(D, E, 100, 4);
        Trace.Result r = fwd(A, 3, Method.PROPORTIONAL);
        assertThat(r.status()).isEqualTo(Trace.Status.PARTIAL);
        assertThat(stopped(r, D, StopReason.DEPTH)).isTrue();
        assertThat(at(r, E)).isNull();
        assertThat(r.nodes()).extracting(Trace.Node::address).doesNotContain(E);
    }

    /** 5: several sources, PROPORTIONAL 30, FIFO 50. */
    @Test void s5SeveralSources() {
        src.flow(S1, A, 60, 1); src.flow(S2, A, 40, 2); src.flow(A, X, 50, 3);
        assertThat(at(fwd(S1, 2, Method.PROPORTIONAL), X).high()).isEqualTo(Fraction.of(30));
        assertThat(at(fwd(S1, 2, Method.FIFO), X).high()).isEqualTo(Fraction.of(50));
    }

    /** 8: a known opening balance dilutes (PROPORTIONAL 50) or leaves first (FIFO 0); unknown: interval [0, 100]. */
    @Test void s8OpeningBalance() {
        src.flow(S, A, 100, 1); src.flow(A, X, 100, 2);
        src.opening.put(A, Optional.of(BigInteger.valueOf(100)));
        assertThat(at(fwd(S, 2, Method.PROPORTIONAL), X).high()).isEqualTo(Fraction.of(50));
        assertThat(at(fwd(S, 2, Method.FIFO), X)).as("nothing of S reaches X").isNull();
        src.opening.put(A, Optional.empty());
        Trace.Result r = fwd(S, 2, Method.PROPORTIONAL);
        assertThat(at(r, X).low()).isEqualTo(Fraction.ZERO);
        assertThat(at(r, X).high()).isEqualTo(Fraction.of(100));
        assertThat(stopped(r, A, StopReason.UNKNOWN_OPENING_BALANCE)).isTrue();
        assertThat(r.status()).isEqualTo(Trace.Status.PARTIAL);
    }

    /** 9: tokens are never mixed. */
    @Test void s9MixedAssets() {
        src.flow(S, A, 100, 1); src.flow(Y, A, 1, ETH, 1); src.flow(A, X, 100, 2); src.flow(A, Z, 1, ETH, 2);
        Trace.Result r = fwd(S, 2, Method.PROPORTIONAL);
        assertThat(at(r, X).high()).isEqualTo(Fraction.of(100));
        assertThat(at(r, Z)).isNull();
        assertThat(r.attributions()).allMatch(t -> t.tokenKey().equals(K));
    }

    /** 10: direct source of A, with its label; dust and an unverified token are not followed. */
    @Test void s10DirectSourceAndWhatIsNotFollowed() {
        src.label(S, LabelCategory.SANCTIONED);
        ValueFlow f = src.flow(S, A, 1_000, 1);
        Trace.Result r = back(A, 1);
        assertThat(at(r, S).high()).isEqualTo(Fraction.of(1_000));
        assertThat(r.edges()).extracting(Trace.Edge::txHash).containsExactly(f.txHash());
        assertThat(r.nodes()).filteredOn(n -> n.address().equals(S)).singleElement()
                .satisfies(n -> assertThat(n.labels()).contains("SANCTIONED (test-list)"));

        Src dust = new Src();
        dust.flow(S, A, 1, 1);
        Trace.Result rd = TraceEngine.run(Trace.Request.of(A, Direction.BACKWARD).withDust(Map.of("USDT", new BigDecimal("10"))), dust);
        assertThat(rd.attributions()).isEmpty();
        assertThat(stopped(rd, S, StopReason.DUST)).isTrue();

        Src fake = new Src();
        fake.flow(S, A, 5_000, FAKE, 1);
        Trace.Result rf = TraceEngine.run(Trace.Request.of(A, Direction.BACKWARD), fake);
        assertThat(rf.attributions()).isEmpty();
        assertThat(stopped(rf, S, StopReason.UNVERIFIED_TOKEN)).isTrue();
    }

    /** 11: three hops back to S with the exact amount; funds that reached B later are not the origin. */
    @Test void s11IndirectSource() {
        src.flow(S, B, 500, 1); src.flow(B, C, 400, 2); src.flow(C, A, 300, 3);
        Trace.Result r = back(A, 3);
        assertThat(at(r, S).high()).isEqualTo(Fraction.of(300));
        assertThat(at(r, S).depth()).isEqualTo(3);

        Src later = new Src();
        later.flow(B, C, 400, 2); later.flow(C, A, 300, 3); later.flow(S, B, 500, 4);
        Trace.Result rl = TraceEngine.run(Trace.Request.of(A, Direction.BACKWARD), later);
        assertThat(at(rl, S)).isNull();
        assertThat(stopped(rl, B, StopReason.COVERAGE_GAP)).as("B spent more than it had: inflows are missing").isTrue();
    }

    /** 12: an address whose flows are not all known: partial result, the attribution is flagged. */
    @Test void s12IncompleteCoverage() {
        src.flow(A, B, 100, 1); src.flow(B, C, 100, 2);
        src.incomplete.add(B);
        Trace.Result r = fwd(A, 2, Method.PROPORTIONAL);
        assertThat(r.status()).isEqualTo(Trace.Status.PARTIAL);
        assertThat(stopped(r, B, StopReason.COVERAGE_GAP)).isTrue();
        assertThat(at(r, C).partial()).isTrue();
        assertThat(at(r, B).partial()).isFalse();
    }

    /** 13: a flow given twice, and one TRC-20 transfer under its live and its history id, count once. */
    @Test void s13Duplicates() {
        ValueFlow ab = src.flow(A, B, 100, 1);
        src.flows.add(ab); // same event id again
        src.flow(B, C, 100, 2);
        Trace.Result r = fwd(A, 2, Method.PROPORTIONAL);
        assertThat(at(r, C).high()).isEqualTo(Fraction.of(100));
        assertThat(at(r, B).high()).isEqualTo(Fraction.of(100));

        Token usdt = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 0);
        Address ta = new Address(Network.TRON, "TAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), tb = new Address(Network.TRON, "TBbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        String tx = "a".repeat(64);
        Src tron = new Src();
        tron.flows.add(new ValueFlow("tron:" + tx + ":4", Network.TRON, tx, T0, ta, tb, usdt, BigInteger.valueOf(100)));
        tron.flows.add(new ValueFlow("tron:" + tx + ":trc20:0123456789abcdef:0", Network.TRON, tx, T0, ta, tb, usdt, BigInteger.valueOf(100)));
        Trace.Result rt = TraceEngine.run(Trace.Request.of(ta, Direction.FORWARD), tron);
        assertThat(rt.attributions()).singleElement().satisfies(t -> assertThat(t.high()).isEqualTo(Fraction.of(100)));
        assertThat(rt.edges()).singleElement().satisfies(e -> {
            assertThat(e.eventId()).isEqualTo("tron:" + tx + ":4");
            assertThat(e.aliases()).containsExactly("tron:" + tx + ":trc20:0123456789abcdef:0");
        });
    }

    @Test void theSameRequestOnTheSameFlowsGivesTheSameResult() {
        src.flow(S1, A, 60, 1); src.flow(S2, A, 40, 2); src.flow(A, X, 50, 3); src.flow(A, Y, 33, 4); src.flow(X, Z, 7, 5);
        Trace.Result first = fwd(S1, 3, Method.PROPORTIONAL);
        java.util.Collections.reverse(src.flows); // another source order
        Trace.Result second = fwd(S1, 3, Method.PROPORTIONAL);
        assertThat(second).isEqualTo(first);
        assertThat(second.toString()).isEqualTo(first.toString());
    }

    /** An address with too many flows (service-like) is not expanded, and the result says so. */
    @Test void aServiceLikeAddressIsNotExpanded() {
        src.flow(A, B, 100, 1);
        for (int i = 0; i < 6; i++) src.flow(B, a("s" + i), 10, 2);
        src.flow(B, C, 40, 3);
        Trace.Result r = TraceEngine.run(Trace.Request.of(A, Direction.FORWARD).withLimits(new Trace.Limits(3, 500, 20_000, 5)), src);
        assertThat(stopped(r, B, StopReason.SERVICE_LIKE)).isTrue();
        assertThat(at(r, B).high()).isEqualTo(Fraction.of(100));
        assertThat(at(r, C)).isNull();
        assertThat(r.status()).isEqualTo(Trace.Status.PARTIAL);
    }

    @Test void theNodeAndReadLimitsStopTheTraceAndSaySo() {
        for (int i = 0; i < 10; i++) src.flow(A, a("10" + i), 100, 1);
        Trace.Result nodes = TraceEngine.run(Trace.Request.of(A, Direction.FORWARD).withLimits(new Trace.Limits(2, 4, 20_000)), src);
        assertThat(nodes.counters().nodes()).isEqualTo(4);
        assertThat(stopped(nodes, A, StopReason.NODES)).isTrue();
        assertThat(nodes.status()).isEqualTo(Trace.Status.PARTIAL);

        Trace.Result reads = TraceEngine.run(Trace.Request.of(A, Direction.FORWARD).withLimits(new Trace.Limits(2, 500, 12)), src);
        assertThat(reads.counters().flowsRead()).isLessThanOrEqualTo(12);
        assertThat(reads.stops()).anyMatch(s -> s.reason() == StopReason.FLOWS_READ);
        assertThat(reads.status()).isEqualTo(Trace.Status.PARTIAL);
    }

    /** 8 hops, each address diluting the traced value with 7 of its own: exactly 3 x (3/10)^7 at the end, no drift. */
    @Test void exactFractionsDoNotDriftOverEightHops() {
        src.flow(S, a("801"), 3, 1);
        for (int i = 1; i <= 7; i++) {
            src.flow(a("90" + i), a("80" + i), 7, 10 * i - 5);
            src.flow(a("80" + i), a("80" + (i + 1)), 3, 10 * i);
        }
        Trace.Result r = TraceEngine.run(Trace.Request.of(S, Direction.FORWARD).withLimits(new Trace.Limits(8, 500, 20_000))
                .withMinShare(new Fraction(BigInteger.ONE, BigInteger.TEN.pow(9))), src);
        Fraction expected = Fraction.of(3);
        for (int i = 0; i < 7; i++) expected = expected.times(new Fraction(BigInteger.valueOf(3), BigInteger.TEN));
        assertThat(at(r, a("808")).high()).isEqualTo(expected);
        assertThat(at(r, a("808")).depth()).isEqualTo(8);
        assertThat(r.status()).isEqualTo(Trace.Status.COMPLETE);
    }
}
