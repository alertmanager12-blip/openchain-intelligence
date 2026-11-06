package io.openchain.blockchain.backfill;

import static org.assertj.core.api.Assertions.*;

import io.openchain.blockchain.TestSupport.RecordingSleeper;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.model.TokenFreeze;
import io.openchain.core.provider.ProviderException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** Chunking, adaptive halving, resume after interruption and idempotency of {@link FreezeBackfill} (fake scanner). */
class FreezeBackfillTest {
    static final Token USDT = new Token(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6);

    /** Freezes at blocks 5, 250, 251 (same tx), 999; ranges wider than {@code maxRange} are refused. */
    static final class FakeScanner implements FreezeLogScanner {
        final List<long[]> queries = new ArrayList<>();
        long maxRange = Long.MAX_VALUE, safeHead = 1_000;
        int failAt = -1; // query number that fails with a transient error

        @Override public Network network() { return Network.ETHEREUM; }
        @Override public long backfillSafeHead() { return safeHead; }

        @Override public List<TokenFreeze> scanFreezes(long from, long to) {
            queries.add(new long[]{from, to});
            if (queries.size() == failAt) throw new ProviderException("HTTP 503", true);
            if (to - from + 1 > maxRange) throw new RangeTooLargeException("query returned more than 10000 results", null);
            List<TokenFreeze> out = new ArrayList<>();
            for (long b : new long[]{999, 251, 250, 5}) { // unordered on purpose
                if (b >= from && b <= to) out.add(freeze(b, b == 999 ? TokenFreeze.Action.UNFREEZE : TokenFreeze.Action.FREEZE));
            }
            if (from <= 250 && to >= 250) out.add(freeze(250, TokenFreeze.Action.FREEZE)); // duplicate in the same answer
            return out;
        }
    }

    static TokenFreeze freeze(long block, TokenFreeze.Action action) {
        return new TokenFreeze(Network.ETHEREUM, "0x" + "%064x".formatted(block), 1, block, "0x" + "%064x".formatted(block + 7),
                Instant.ofEpochSecond(1_700_000_000L + block * 12), new Address(Network.ETHEREUM, "0x" + "%040x".formatted(block)),
                USDT, "tether", action, action == TokenFreeze.Action.FREEZE ? "AddedBlackList" : "RemovedBlackList");
    }

    final FakeScanner scanner = new FakeScanner();
    final InMemoryFreezeBackfillStore store = new InMemoryFreezeBackfillStore();
    final List<TokenFreeze> applied = new ArrayList<>();
    final RecordingSleeper sleeper = new RecordingSleeper();

    FreezeBackfill backfill(long from, Long to, int chunk, int min) {
        return new FreezeBackfill("freezes:ethereum", scanner, store, new FreezeBackfill.Config(from, to, chunk, min, Duration.ofMillis(50)),
                applied::add, sleeper, Clock.systemUTC());
    }

    @Test void scansInChunksUpToTheSafeHeadAndAppliesInBlockOrder() {
        FreezeBackfill.Result r = backfill(0, null, 300, 10).run(() -> false);
        assertThat(scanner.queries).extracting(q -> q[0] + ".." + q[1]).containsExactly("0..299", "300..599", "600..899", "900..1000");
        assertThat(r.complete()).isTrue();
        assertThat(r.lastScannedBlock()).isEqualTo(1000);
        assertThat(r.chunks()).isEqualTo(4);
        assertThat(applied).extracting(TokenFreeze::blockNumber).containsExactly(5L, 250L, 251L, 999L); // deduplicated, ordered
        assertThat(sleeper.sleeps).hasSize(3).allMatch(d -> d.equals(Duration.ofMillis(50))); // pause between chunks only
        assertThat(store.load("freezes:ethereum")).hasValueSatisfying(p -> {
            assertThat(p.lastScannedBlock()).isEqualTo(1000);
            assertThat(p.freezesFound()).isEqualTo(4);
        });
    }

    @Test void halvesTheChunkOnRangeErrorsAndKeepsTheSmallerSize() {
        scanner.maxRange = 120;
        FreezeBackfill.Result r = backfill(0, 499L, 500, 50).run(() -> false);
        // 500 -> 250 -> 125 -> 62 (refused 3 times), then 62-block chunks
        assertThat(r.halvings()).isEqualTo(3);
        assertThat(r.finalChunkSize()).isEqualTo(62);
        assertThat(scanner.queries.subList(0, 4)).extracting(q -> q[1] - q[0] + 1).containsExactly(500L, 250L, 125L, 62L);
        assertThat(scanner.queries.subList(3, scanner.queries.size())).allMatch(q -> q[1] - q[0] + 1 <= 62);
        assertThat(r.complete()).isTrue();
        assertThat(applied).extracting(TokenFreeze::blockNumber).containsExactly(5L, 250L, 251L);
    }

    @Test void givesUpWhenEvenTheMinimumChunkIsTooLarge() {
        scanner.maxRange = 5;
        assertThatThrownBy(() -> backfill(0, 100L, 40, 10).run(() -> false)).isInstanceOf(ProviderException.class)
                .hasMessageContaining("minimum chunk size 10");
        assertThat(store.load("freezes:ethereum")).isEmpty();
    }

    @Test void resumesAfterAnInterruptionWithoutRescanningOrReapplying() {
        scanner.failAt = 3; // third chunk fails (transient): the run stops, progress of chunks 1-2 is kept
        assertThatThrownBy(() -> backfill(0, null, 300, 10).run(() -> false)).isInstanceOf(ProviderException.class);
        assertThat(store.load("freezes:ethereum").orElseThrow().lastScannedBlock()).isEqualTo(599);
        assertThat(applied).hasSize(3);

        scanner.queries.clear();
        scanner.failAt = -1;
        FreezeBackfill.Result r = backfill(0, null, 300, 10).run(() -> false); // "after restart"
        assertThat(scanner.queries.getFirst()[0]).isEqualTo(600);
        assertThat(r.complete()).isTrue();
        assertThat(applied).extracting(TokenFreeze::blockNumber).containsExactly(5L, 250L, 251L, 999L);

        // nothing left to do: no query; cancellation is honoured before any work
        scanner.queries.clear();
        assertThat(backfill(0, null, 300, 10).run(() -> false).complete()).isTrue();
        assertThat(scanner.queries).isEmpty();
        scanner.safeHead = 2_000;
        AtomicBoolean cancel = new AtomicBoolean(true);
        FreezeBackfill.Result c = backfill(0, null, 300, 10).run(cancel::get);
        assertThat(c.cancelled()).isTrue();
        assertThat(scanner.queries).isEmpty();
    }

    @Test void storeIsIdempotentAndReplayReappliesStoredFreezesInOrder() {
        backfill(0, 400L, 1000, 10).run(() -> false);
        FreezeBackfill again = backfill(0, 400L, 1000, 10);
        // a rescan of an earlier start (range widened) stores each event id once
        store.saveChunk(store.load("freezes:ethereum").orElseThrow(), List.of(freeze(250, TokenFreeze.Action.FREEZE), freeze(5, TokenFreeze.Action.FREEZE)));
        assertThat(store.freezes("freezes:ethereum")).extracting(TokenFreeze::blockNumber).containsExactly(5L, 250L, 251L);
        applied.clear();
        assertThat(again.replayStored()).isEqualTo(3);
        assertThat(applied).extracting(TokenFreeze::blockNumber).containsExactly(5L, 250L, 251L);
    }

    @Test void anEarlierStartThanTheStoredOneRescansFromTheNewStart() {
        backfill(500, null, 1000, 10).run(() -> false);
        scanner.queries.clear();
        backfill(0, null, 1000, 10).run(() -> false);
        assertThat(scanner.queries).extracting(q -> q[0]).containsExactly(0L, 1000L); // 0..999 then 1000..1000
        assertThat(store.load("freezes:ethereum").orElseThrow().fromBlock()).isZero();
    }

    @Test void invalidConfigIsRejected() {
        assertThatThrownBy(() -> new FreezeBackfill.Config(-1, null, 10, 1, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreezeBackfill.Config(0, null, 10, 20, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FreezeBackfill.Config(10, 5L, 10, 1, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
