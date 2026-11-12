package io.openchain.intelligence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.core.model.ChainTransaction;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenTransfer;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.DetectionEngine;
import io.openchain.intelligence.detection.InMemorySignalRepository;
import io.openchain.intelligence.flow.InMemoryFlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.InMemoryLabelRepository;
import io.openchain.intelligence.profile.WalletProfileService;
import io.openchain.intelligence.risk.InMemoryRiskScoreRepository;
import io.openchain.intelligence.risk.RiskScoringEngine;
import io.openchain.intelligence.risk.RiskScoringService;
import io.openchain.intelligence.risk.ScoringConfig;
import java.lang.ref.WeakReference;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Step 3 option A: sharing token and address instances changes object identity only. Differential runs (same events
 * through a pipeline that shares nothing and pipelines that share, including with every hash forced equal), collision,
 * eviction/re-inclusion, balance, concurrency and reference-release checks.
 */
class CanonicalInstancesTest {
    static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC);

    static IntelligencePipeline pipeline(Function<WalletProfileService, CanonicalInstances> canonical) {
        var flows = new InMemoryFlowStore();
        var labels = new InMemoryLabelRepository();
        var signals = new InMemorySignalRepository();
        var scores = new InMemoryRiskScoreRepository();
        var profiles = new WalletProfileService(CLOCK, 10, scores::history);
        var scoring = new RiskScoringService(new RiskScoringEngine(ScoringConfig.defaults()), labels, signals, profiles::profileOrEmpty, scores, CLOCK);
        return new IntelligencePipeline(flows, profiles, labels, signals, DetectionEngine.withDefaultDetectors(signals), scoring,
                DetectionConfig.defaults(), CLOCK, canonical.apply(profiles));
    }

    /** A fresh copy of a string (a new backing array), as a decoder produces for every event. */
    static String fresh(String s) { return s == null ? null : new String(s.toCharArray()); }
    static Address addr(Network n, String v) { return new Address(n, fresh(v)); }
    static Token tok(Token t) { return new Token(t.network(), fresh(t.contract()), fresh(t.symbol()), t.decimals()); }

    // tokens that must never be confused: same symbol other contract, same contract other network, other decimals
    static final List<Token> TOKENS = List.of(
            new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6),
            new Token(Network.TRON, "TXLAQ63Xg1NAzckPwKHvzw7CSEmLMEqcdj", "USDT", 6),         // same symbol, other contract
            new Token(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6),
            new Token(Network.POLYGON, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6),  // same contract, other network
            new Token(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 18), // other decimals
            new Token(Network.ETHEREUM, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", "USDC", 6));

    static List<Address> pool() {
        List<Address> p = new ArrayList<>();
        Random r = new Random(3);
        for (int i = 0; i < 30; i++) p.add(new Address(Network.TRON, ("T" + Long.toString(Math.abs(r.nextLong()), 36) + "x".repeat(40)).substring(0, 34)));
        for (int i = 0; i < 12; i++) {
            String hex = "0x" + String.format("%040x", new BigInteger(160, r));
            p.add(new Address(Network.ETHEREUM, hex));
            p.add(new Address(Network.POLYGON, hex)); // same value on another network: a different address
        }
        // two values whose String hash codes collide ("Aa" and "BB"): distinct addresses with equal hash codes
        p.add(new Address(Network.TRON, "TAaCollide000000000000000000000000".substring(0, 34)));
        p.add(new Address(Network.TRON, "TBBCollide000000000000000000000000".substring(0, 34)));
        return p;
    }

    /** Everything observable after one operation, compared between runs. */
    record Step(String op, Object outcome) {}

    /**
     * The same scripted event stream (transfers, replays, native transactions, retractions, re-inclusions, evictions,
     * balances), every event built from fresh objects; returns the per-operation outcomes and the final state.
     */
    static List<Object> run(IntelligencePipeline p) {
        List<Address> pool = pool();
        Random r = new Random(11);
        List<Step> steps = new ArrayList<>();
        List<TokenTransfer> sent = new ArrayList<>();
        List<String> retracted = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < 2_500; i++) {
            Instant t = T0.plusSeconds(30L * i);
            int op = r.nextInt(100);
            if (op < 78 || sent.isEmpty()) {
                Token proto = TOKENS.get(r.nextInt(TOKENS.size()));
                List<Address> sameNet = pool.stream().filter(a -> a.network() == proto.network()).toList();
                Address from = sameNet.get(r.nextInt(sameNet.size())), to = sameNet.get(r.nextInt(sameNet.size()));
                BigInteger raw = BigInteger.valueOf(r.nextInt(5) == 0 ? 50_000_000_000L + r.nextInt(1_000_000) : 1_000_000L + r.nextInt(900_000_000));
                String hash = String.format("%064x", new BigInteger(256, r));
                TokenTransfer tt = new TokenTransfer(proto.network(), hash, r.nextInt(4), 1_000 + i, "0xb" + i, t,
                        addr(from.network(), from.value()), addr(to.network(), to.value()), tok(proto), raw);
                sent.add(tt); ids.add(tt.eventId());
                if (i % 3 == 0) p.balances().recordSenderBalanceBefore(fresh(tt.eventId()), BigDecimal.valueOf(i, 2));
                var res = p.onTransfer(tt);
                steps.add(new Step("transfer", List.of(res.duplicate(), res.newSignals().stream().map(Signal::id).toList(), res.scores())));
            } else if (op < 83) { // replay of an earlier event with fresh objects
                TokenTransfer o = sent.get(r.nextInt(sent.size()));
                TokenTransfer again = new TokenTransfer(o.network(), fresh(o.txHash()), o.logIndex(), o.blockNumber(), o.blockHash(), o.timestamp(),
                        addr(o.from().network(), o.from().value()), addr(o.to().network(), o.to().value()), tok(o.token()), o.rawAmount());
                var res = p.onTransfer(again);
                steps.add(new Step("replay", List.of(res.duplicate(), res.newSignals().stream().map(Signal::id).toList())));
            } else if (op < 88) { // native value
                List<Address> tron = pool.stream().filter(a -> a.network() == Network.TRON).toList();
                Address from = tron.get(r.nextInt(tron.size())), to = tron.get(r.nextInt(tron.size()));
                var tx = new ChainTransaction(Network.TRON, String.format("%064x", new BigInteger(256, r)), 1_000 + i, "0xb" + i, t,
                        addr(Network.TRON, from.value()), addr(Network.TRON, to.value()), BigInteger.valueOf(1_000_000L + r.nextInt(1_000_000_000)),
                        BigInteger.ONE, true, null, false);
                var res = p.onTransaction(tx);
                steps.add(new Step("native", List.of(res.duplicate(), res.newSignals().stream().map(Signal::id).toList())));
            } else if (op < 93) { // reorganisation: retract a flow
                String id = sent.get(r.nextInt(sent.size())).eventId();
                var rr = p.retractFlow(fresh(id));
                if (rr.applied()) retracted.add(id);
                steps.add(new Step("retract", List.of(rr.applied(), rr.profileSidesReverted(), ids(rr.removed()), ids(rr.recomputed()), ids(rr.added()), rr.scores())));
            } else if (op < 97 && !retracted.isEmpty()) { // re-inclusion in another block, fresh objects
                String id = retracted.remove(r.nextInt(retracted.size()));
                TokenTransfer o = sent.stream().filter(x -> x.eventId().equals(id)).findFirst().orElseThrow();
                var res = p.onTransfer(new TokenTransfer(o.network(), fresh(o.txHash()), o.logIndex(), o.blockNumber() + 1, "0xre" + i, o.timestamp(),
                        addr(o.from().network(), o.from().value()), addr(o.to().network(), o.to().value()), tok(o.token()), o.rawAmount()));
                steps.add(new Step("reinclusion", List.of(res.duplicate(), res.newSignals().stream().map(Signal::id).toList(), res.scores())));
            } else { // working-set eviction (flows then idle profiles), then later events may revisit evicted wallets
                int flowsGone = p.flows().evictBefore(n -> t.minusSeconds(3_000), a -> false);
                var profilesGone = p.profiles().evictIdle(n -> t.minusSeconds(6_000), a -> false);
                steps.add(new Step("evict", List.of(flowsGone, new java.util.TreeSet<>(profilesGone.stream().map(Address::toString).toList()))));
            }
        }
        List<Object> state = new ArrayList<>(steps);
        for (Address a : pool) {
            state.add(List.of(a, p.flows().outgoing(a), p.flows().incoming(a), p.profiles().profileOrEmpty(a),
                    p.scoring().latest(a), sorted(p.signals().findByAddress(a))));
        }
        state.add(sorted(p.signals().findAll()));
        for (String id : ids) state.add(List.of(id, p.balances().senderBalanceBefore(id)));
        state.add(p.flows().size());
        state.add(p.profiles().size());
        return state;
    }

    static List<String> ids(List<Signal> s) { return s.stream().map(Signal::id).sorted().toList(); }
    static List<Signal> sorted(List<Signal> s) { return s.stream().sorted(Comparator.comparing(Signal::id)).toList(); }

    @Test void sharingInstancesChangesNothingObservable() {
        List<Object> reference = run(pipeline(p -> CanonicalInstances.NONE));
        IntelligencePipeline shared = pipeline(p -> null); // production default
        List<Object> withSharing = run(shared);
        assertThat(withSharing).hasSameSizeAs(reference);
        for (int i = 0; i < reference.size(); i++) assertThat(withSharing.get(i)).as("item %d", i).isEqualTo(reference.get(i));

        // the run is meaningful: signals, retractions, re-inclusions, evictions and sharing all happened
        assertThat(reference.stream().filter(x -> x instanceof Step s && s.op().equals("retract") && ((List<?>) s.outcome()).get(0).equals(true))).isNotEmpty();
        assertThat(reference.stream().filter(x -> x instanceof Step s && s.op().equals("reinclusion"))).isNotEmpty();
        assertThat(shared.signals().findAll()).isNotEmpty();
        var st = shared.canonicalInstances().stats();
        java.util.Map<String, Long> ops = new java.util.TreeMap<>();
        reference.forEach(x -> { if (x instanceof Step s) ops.merge(s.op(), 1L, Long::sum); });
        System.out.println("DIFF|items compared " + reference.size() + "|ops " + ops + "|signals " + shared.signals().findAll().size()
                + "|flows " + shared.flows().size() + "|profiles " + shared.profiles().size() + "|sharing " + st);
        assertThat(st.tokens()).isEqualTo(TOKENS.size() + 1); // + native TRX (its shared NativeTokens descriptor)
        assertThat(st.tokenHits()).isGreaterThan(1_000);
        assertThat(st.addressesShared()).isGreaterThan(1_000);
    }

    @Test void forcedHashCollisionsAndATinyTokenTableChangeNothingObservable() {
        List<Object> reference = run(pipeline(p -> CanonicalInstances.NONE));
        // every token hash equal: lookups degrade to equality checks, never to a merge
        List<Object> collide = run(pipeline(p -> new CanonicalInstances(4_096, p::canonicalAddress, o -> 0)));
        // capacity 2: tokens are constantly forgotten and re-registered (LRU path)
        List<Object> tiny = run(pipeline(p -> new CanonicalInstances(2, p::canonicalAddress, o -> 0)));
        for (int i = 0; i < reference.size(); i++) {
            assertThat(collide.get(i)).as("collisions, item %d", i).isEqualTo(reference.get(i));
            assertThat(tiny.get(i)).as("tiny table, item %d", i).isEqualTo(reference.get(i));
        }
    }

    @Test void collidingTokensAreNeverMerged() {
        CanonicalInstances c = new CanonicalInstances(4_096, a -> a, o -> 0);
        List<Token> all = new ArrayList<>(TOKENS);
        all.add(new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "UNKNOWN", 6)); // same contract, other symbol
        for (Network n : Network.values()) all.add(Token.nativeOf(n));
        for (Token t : all) assertThat(c.token(tok(t))).isEqualTo(t); // first registration: the value itself
        for (Token t : all) {
            Token a = c.token(tok(t)), b = c.token(tok(t));
            assertThat(a).isEqualTo(t).isSameAs(b);           // equal values share one instance...
            for (Token u : all) if (!u.equals(t)) assertThat(c.token(tok(u))).isNotSameAs(a).isEqualTo(u); // ...distinct never
        }
        assertThat(c.stats().tokens()).isEqualTo(all.size());
    }

    @Test void addressesWithEqualHashCodesOrValuesOnOtherNetworksAreNeverMerged() {
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        Address aa = new Address(Network.TRON, "TAaCollide0000000000000000000000000".substring(0, 34));
        Address bb = new Address(Network.TRON, "TBBCollide0000000000000000000000000".substring(0, 34));
        assertThat(aa.hashCode()).isEqualTo(bb.hashCode()).as("forced collision");
        Address eth = new Address(Network.ETHEREUM, "0x" + "ab".repeat(20)), poly = new Address(Network.POLYGON, "0x" + "ab".repeat(20));
        WalletProfileService profiles = new WalletProfileService(CLOCK);
        CanonicalInstances c = new CanonicalInstances(16, profiles::canonicalAddress);
        Token usdt = TOKENS.get(0);
        profiles.ingest(new ValueFlow("tron:01:0", Network.TRON, "01", T0, aa, bb, usdt, BigInteger.TEN));
        profiles.ingest(new ValueFlow("ethereum:02:0", Network.ETHEREUM, "02", T0, eth, eth, TOKENS.get(2), BigInteger.TEN));
        assertThat(c.address(addr(Network.TRON, bb.value()))).isSameAs(profiles.canonicalAddress(bb)).isEqualTo(bb).isNotEqualTo(aa);
        assertThat(c.address(addr(Network.TRON, aa.value()))).isEqualTo(aa).isNotSameAs(c.address(bb));
        Address polyIn = addr(Network.POLYGON, poly.value());
        assertThat(c.address(polyIn)).isSameAs(polyIn).isNotEqualTo(eth); // no profile on Polygon: unchanged, never Ethereum's
        // a source returning a different value is ignored (defensive)
        CanonicalInstances wrong = new CanonicalInstances(16, a -> aa);
        assertThat(wrong.address(bb)).isSameAs(bb);
    }

    @Test void tokenTableStaysBoundedAndConcurrentLookupsAgree() throws Exception {
        CanonicalInstances c = new CanonicalInstances(100, a -> a);
        for (int i = 0; i < 1_000; i++) c.token(new Token(Network.ETHEREUM, String.format("0x%040x", i), "T" + i, 18));
        assertThat(c.stats().tokens()).isEqualTo(100);

        CanonicalInstances shared = new CanonicalInstances(4_096, a -> a);
        ExecutorService ex = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<List<Token>>> results = new ArrayList<>();
        for (int th = 0; th < 8; th++) results.add(ex.submit(() -> {
            List<Token> got = new ArrayList<>();
            for (int i = 0; i < 20_000; i++) got.add(shared.token(tok(TOKENS.get(i % TOKENS.size()))));
            return got;
        }));
        ex.shutdown();
        assertThat(ex.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        for (var f : results) {
            List<Token> got = f.get();
            for (int i = 0; i < got.size(); i++) {
                assertThat(got.get(i)).isEqualTo(TOKENS.get(i % TOKENS.size()));
                assertThat(got.get(i)).isSameAs(shared.token(TOKENS.get(i % TOKENS.size())));
            }
        }
        assertThat(shared.stats().tokens()).isEqualTo(TOKENS.size());
    }

    @Test void concurrentIngestionGivesTheSameProfilesAsSequentialIngestionWithoutSharing() throws Exception {
        List<Address> pool = pool().stream().filter(a -> a.network() == Network.TRON).toList();
        Random r = new Random(5);
        List<ValueFlow> flows = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            Address f = pool.get(r.nextInt(pool.size())), t = pool.get(r.nextInt(pool.size()));
            String h = String.format("%064x", new BigInteger(256, r));
            flows.add(new ValueFlow("tron:" + h + ":0", Network.TRON, h, T0.plusSeconds(i), f, t, TOKENS.get(i % 2), BigInteger.valueOf(1 + r.nextInt(1_000))));
        }
        WalletProfileService seq = new WalletProfileService(CLOCK);
        InMemoryFlowStore seqStore = new InMemoryFlowStore();
        for (ValueFlow f : flows) if (seqStore.add(f)) seq.ingest(f);

        WalletProfileService par = new WalletProfileService(CLOCK);
        InMemoryFlowStore parStore = new InMemoryFlowStore();
        CanonicalInstances c = new CanonicalInstances(4_096, par::canonicalAddress);
        ExecutorService ex = Executors.newFixedThreadPool(6);
        for (int th = 0; th < 6; th++) {
            int k = th;
            ex.submit(() -> {
                for (int i = k; i < flows.size(); i += 6) {
                    ValueFlow o = flows.get(i);
                    ValueFlow f = c.flow(new ValueFlow(fresh(o.eventId()), o.network(), fresh(o.txHash()), o.timestamp(),
                            addr(Network.TRON, o.from().value()), addr(Network.TRON, o.to().value()), tok(o.token()), o.rawAmount()));
                    if (parStore.add(f)) par.ingest(f);
                }
            });
        }
        ex.shutdown();
        assertThat(ex.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(parStore.size()).isEqualTo(seqStore.size());
        for (Address a : pool) {
            assertThat(par.profileOrEmpty(a)).isEqualTo(seq.profileOrEmpty(a));
            List<ValueFlow> out = parStore.outgoing(a);
            assertThat(out).containsExactlyInAnyOrderElementsOf(seqStore.outgoing(a));
            // sharing is best effort while threads race on a wallet that has no profile yet (each keeps its own, equal,
            // instance until a profile exists): at most one instance per thread, values always equal
            long identities = out.stream().map(f -> System.identityHashCode(f.from())).distinct().count();
            assertThat(identities).isLessThanOrEqualTo(6);
            assertThat(out).allSatisfy(f -> assertThat(f.from()).isEqualTo(a));
        }
    }

    @Test void canonicalAddressesAreReleasedWithTheirProfileAndFlows() {
        WalletProfileService profiles = new WalletProfileService(CLOCK);
        InMemoryFlowStore store = new InMemoryFlowStore();
        CanonicalInstances c = new CanonicalInstances(4_096, profiles::canonicalAddress);
        WeakReference<Address> watched = ingestAndWatch(c, profiles, store);
        // still referenced: profile and flow hold it
        assertThat(watched.get()).isNotNull();
        store.evictBefore(n -> T0.plusSeconds(1_000), a -> false);
        profiles.evictIdle(n -> T0.plusSeconds(1_000), a -> false);
        for (int i = 0; i < 50 && watched.get() != null; i++) { System.gc(); try { Thread.sleep(20); } catch (InterruptedException e) { break; } }
        assertThat(watched.get()).as("no reference left in CanonicalInstances once the profile and its flows are gone").isNull();
        // a later flow of the same wallet gets a new instance (no stale table)
        Address again = addr(Network.TRON, "TWatchedWallet000000000000000000000".substring(0, 34));
        assertThat(c.address(again)).isSameAs(again);
    }

    private static WeakReference<Address> ingestAndWatch(CanonicalInstances c, WalletProfileService profiles, InMemoryFlowStore store) {
        Address w = addr(Network.TRON, "TWatchedWallet000000000000000000000".substring(0, 34));
        Address peer = addr(Network.TRON, "TPeerWallet00000000000000000000000".substring(0, 34));
        for (int i = 0; i < 3; i++) {
            ValueFlow f = c.flow(new ValueFlow("tron:" + "0".repeat(63) + i + ":0", Network.TRON, "0".repeat(63) + i, T0.plusSeconds(i),
                    addr(Network.TRON, w.value()), peer, tok(TOKENS.get(0)), BigInteger.TEN));
            if (store.add(f)) profiles.ingest(f);
        }
        return new WeakReference<>(profiles.canonicalAddress(w));
    }
}
