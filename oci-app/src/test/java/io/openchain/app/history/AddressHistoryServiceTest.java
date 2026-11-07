package io.openchain.app.history;

import static org.assertj.core.api.Assertions.*;

import io.openchain.app.api.Dto;
import io.openchain.app.history.AddressHistoryStore.Progress;
import io.openchain.app.history.AddressHistoryStore.Status;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.history.AddressHistorySource;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.provider.ProviderException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Orchestration of the on-demand address history (ADR-0020): range, resume, idempotency, caps, restore, coverage. */
class AddressHistoryServiceTest {

    static final Network NET = Network.ETHEREUM;
    static final Address ME = new Address(NET, "0x0f2897ec295cb6d6776c10ad16a1d4546c25ec06");
    static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    /** Runs submitted tasks inline (deterministic tests). */
    static final class Inline extends AbstractExecutorService {
        boolean shutdown;
        @Override public void execute(Runnable r) { r.run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    /** Scripted source: walks [from, to] backwards in chunks of 100 blocks, one transfer per block listed in {@code blocks}. */
    static final class FakeSource implements AddressHistorySource {
        final List<Long> blocks;
        final List<String> calls = new ArrayList<>();
        int failOnCall = -1;
        long head = 1_000;

        FakeSource(List<Long> blocks) { this.blocks = blocks; }

        @Override public String sourceId() { return "eth_getLogs"; }
        @Override public Network network() { return NET; }
        @Override public Coverage coverage() { return new Coverage(true, true, false, false); }
        @Override public long historySafeHead() { return head; }

        @Override public HistoryPage fetch(Address address, long from, long to, String cursor) {
            calls.add(from + ".." + to + "@" + cursor);
            if (calls.size() == failOnCall) throw new ProviderException("rate limited", true);
            long upper = cursor == null ? to : Long.parseLong(cursor);
            long lower = Math.max(from, upper - 99);
            List<ChainEvent> events = new ArrayList<>();
            for (long b : blocks) {
                if (b >= lower && b <= upper) {
                    events.add(event(b));
                    events.add(event(b)); // the same log returned by both topic queries: deduplicated
                }
            }
            return new HistoryPage(events, lower, null, lower == from ? null : String.valueOf(lower - 1), 2);
        }
    }

    static ChainEvent event(long block) {
        return new ChainEvent("ethereum:0xtx" + block + ":0", ChainEvent.EventType.TOKEN_TRANSFER, NET, block, "0xb" + block,
                T0.plusSeconds(block * 12), T0, false, Map.of("transaction.hash", "0xtx" + block, "transaction.from", "0x" + "1".repeat(40),
                "transaction.to", ME.value(), "transaction.amount", new BigDecimal("10"), "transaction.amount_raw", new BigDecimal("10000000"),
                "transaction.token.symbol", "USDT", "transaction.token.decimals", 6L));
    }

    final InMemoryAddressHistoryStore store = new InMemoryAddressHistoryStore();
    final List<List<ChainEvent>> applied = new ArrayList<>();
    final Clock clock = Clock.fixed(Instant.parse("2026-11-07T12:00:00Z"), ZoneOffset.UTC);

    AddressHistoryService service(OptionalLong firstLive) {
        return new AddressHistoryService(store, applied::add, n -> firstLive, clock, new Inline());
    }

    static AddressHistoryService.Config cfg(long maxBlocks, int maxPages, long maxEvents) {
        return new AddressHistoryService.Config(maxBlocks, maxPages, maxEvents, Duration.ZERO);
    }

    String id() { return AddressHistoryStore.idOf(NET, ME.value()); }

    @Test void coversTheBlocksBeforeTheLiveIndexerAndAppliesEventsOnceInChronologicalOrder() {
        FakeSource src = new FakeSource(List.of(650L, 880L, 720L));
        AddressHistoryService s = service(OptionalLong.of(901));
        s.register(src, cfg(300, 100, 1_000));
        s.ensure(ME);

        // range = [toBlock - maxBlocks + 1, first live block - 1], walked newest first
        assertThat(src.calls).containsExactly("601..900@null", "601..900@800", "601..900@700");
        Progress p = store.load(id()).orElseThrow();
        assertThat(p.status()).isEqualTo(Status.COMPLETE);
        assertThat(p.coveredFromBlock()).isEqualTo(601);
        assertThat(p.eventsFound()).isEqualTo(3);
        assertThat(applied).singleElement().satisfies(batch ->
                assertThat(batch).extracting(ChainEvent::blockNumber).containsExactly(650L, 720L, 880L));

        Dto.HistoryCoverageDto c = s.coverage(ME);
        System.out.println("COVERAGE: " + c);
        assertThat(c.status()).isEqualTo("COMPLETE");
        assertThat(c.sources()).containsExactly("eth_getLogs");
        assertThat(c.coveredFromBlock()).isEqualTo(601);
        assertThat(c.coveredToBlock()).isEqualTo(900);
        assertThat(c.nativeCovered()).isFalse();
        assertThat(c.internalCovered()).isFalse();
        assertThat(c.message()).isEqualTo("History: ERC-20 transfers and approvals since block 601 (eth_getLogs, 3 event(s)). "
                + "Native transfers and internal transfers are not covered: configure an Erigon node (ADR-0020).");

        // idempotent: an address already covered is not fetched again
        s.ensure(ME);
        assertThat(src.calls).hasSize(3);
        assertThat(applied).hasSize(1);
    }

    @Test void aProviderErrorKeepsTheProgressAndTheNextAnalysisResumesFromTheCursor() {
        FakeSource src = new FakeSource(List.of(950L, 850L, 750L));
        src.failOnCall = 2;
        AddressHistoryService s = service(OptionalLong.empty()); // nothing indexed live yet: up to the safe head
        s.register(src, cfg(300, 100, 1_000));
        s.ensure(ME);

        Progress failed = store.load(id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.lastError()).contains("rate limited");
        assertThat(failed.cursor()).isEqualTo("900");
        assertThat(failed.eventsFound()).isEqualTo(1);
        assertThat(store.events(id())).extracting(ChainEvent::blockNumber).containsExactly(950L);
        assertThat(applied).isEmpty(); // nothing applied before the history is complete
        assertThat(s.coverage(ME).status()).isEqualTo("FAILED");

        s.ensure(ME);
        assertThat(src.calls).containsExactly("701..1000@null", "701..1000@900", "701..1000@900", "701..1000@800");
        assertThat(store.load(id()).orElseThrow().status()).isEqualTo(Status.COMPLETE);
        assertThat(applied).singleElement().satisfies(b -> assertThat(b).extracting(ChainEvent::blockNumber).containsExactly(750L, 850L, 950L));
    }

    @Test void capsMarkTheHistoryTruncatedAndKeepTheNewestEvents() {
        FakeSource src = new FakeSource(List.of(990L, 980L, 890L, 880L, 790L));
        AddressHistoryService s = service(OptionalLong.empty());
        s.register(src, cfg(1_000, 100, 3));
        s.ensure(ME);
        Progress p = store.load(id()).orElseThrow();
        assertThat(p.status()).isEqualTo(Status.TRUNCATED);
        assertThat(p.truncated()).isTrue();
        assertThat(p.eventsFound()).isEqualTo(3);
        assertThat(store.events(id())).extracting(ChainEvent::blockNumber).containsExactly(890L, 980L, 990L);
        assertThat(p.coveredFromBlock()).as("the capped page is not fully covered").isEqualTo(901);
        assertThat(s.coverage(ME).message()).contains("truncated at 3 events: older history is not loaded");

        InMemoryAddressHistoryStore other = new InMemoryAddressHistoryStore();
        AddressHistoryService pages = new AddressHistoryService(other, b -> {}, n -> OptionalLong.empty(), clock, new Inline());
        FakeSource src2 = new FakeSource(List.of());
        pages.register(src2, cfg(1_000, 2, 1_000));
        pages.ensure(ME);
        assertThat(src2.calls).hasSize(2);
        assertThat(other.load(id()).orElseThrow().status()).isEqualTo(Status.TRUNCATED);
    }

    @Test void restartReappliesFinishedHistoriesWithoutRpcAndResumesUnfinishedOnes() {
        FakeSource src = new FakeSource(List.of(950L, 850L));
        AddressHistoryService first = service(OptionalLong.empty());
        first.register(src, cfg(300, 100, 1_000));
        first.ensure(ME);
        Address other = new Address(NET, "0x" + "2".repeat(40));
        String otherId = AddressHistoryStore.idOf(NET, other.value());
        Instant now = clock.instant();
        store.saveChunk(new Progress(otherId, NET, other.value(), "eth_getLogs", Status.RUNNING, 701, 1000, 901L, null, "900", 1, 1, false,
                null, now, now), List.of(event(960)));

        applied.clear();
        FakeSource after = new FakeSource(List.of(850L));
        AddressHistoryService second = service(OptionalLong.empty());
        second.register(after, cfg(300, 100, 1_000));
        assertThat(second.restore()).isEqualTo(1);
        assertThat(after.calls).as("only the unfinished backfill calls the provider, from its cursor")
                .containsExactly("701..1000@900", "701..1000@800");
        assertThat(store.load(otherId).orElseThrow().status()).isEqualTo(Status.COMPLETE);
        assertThat(applied).hasSize(2);
    }

    @Test void networksWithoutSourceAreNotConfigured() {
        AddressHistoryService s = service(OptionalLong.empty());
        assertThat(s.coverage(ME).status()).isEqualTo("NOT_CONFIGURED");
        assertThat(s.coverage(ME).message()).startsWith("No address history source is configured for ethereum");
        s.markSimulated(NET);
        assertThat(s.coverage(ME).message()).startsWith("Simulated demo chain");
        s.ensure(ME);
        assertThat(store.all()).isEmpty();
    }
}
