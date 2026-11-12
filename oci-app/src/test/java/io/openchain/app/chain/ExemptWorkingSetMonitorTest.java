package io.openchain.app.chain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.app.ApiTestSupport;
import io.openchain.app.history.AddressHistoryStore;
import io.openchain.app.history.AddressHistoryStore.Progress;
import io.openchain.app.history.AddressHistoryStore.Status;
import io.openchain.app.watchlist.WatchlistService;
import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Step 2 point 1: the exempt working set is measured per category and per address, and nothing else changes. */
class ExemptWorkingSetMonitorTest extends ApiTestSupport {
    @Autowired ChainIngestionService chains;
    @Autowired IntelligencePipeline intel;
    @Autowired AddressHistoryStore histories;
    @Autowired WatchlistService watchlist;
    @Autowired ExemptWorkingSetMonitor monitor;

    static final Token USDT = new Token(Network.POLYGON, "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "USDT", 6);

    static Address poly(String hex2) { return new Address(Network.POLYGON, "0x" + hex2.repeat(20)); }

    static Progress progress(Address a, Status s, long events) {
        Instant t = Instant.now().minus(Duration.ofHours(2));
        return new Progress(AddressHistoryStore.idOf(a.network(), a.value()), a.network(), a.value(), "eth_getLogs", s, 1, 100,
                null, null, null, events, 1, s == Status.TRUNCATED, s == Status.FAILED ? "boom" : null, t, t);
    }

    @Test void categoriesFlowsSignalsAndProposedCapsAreMeasured() throws Exception {
        Address loaded = poly("e1"), partial = poly("e2"), empty = poly("e3"), failed = poly("e4"), watchedFailed = poly("e5"),
                busy = poly("e6"), peer = poly("e7");
        histories.save(progress(loaded, Status.COMPLETE, 12));
        histories.save(progress(partial, Status.TRUNCATED, 5_000));
        histories.save(progress(empty, Status.TRUNCATED, 0));
        histories.save(progress(failed, Status.FAILED, 0));
        histories.save(progress(watchedFailed, Status.FAILED, 0));
        histories.save(progress(busy, Status.TRUNCATED, 5_000));
        watchlist.add(watchedFailed, "client treasury", "test");
        Instant now = Instant.now();
        List<ValueFlow> flows = new ArrayList<>();
        flows.add(new ValueFlow("polygon:0xex1:0", Network.POLYGON, "0xex1", now.minusSeconds(60), loaded, partial, USDT, BigInteger.TEN)); // shared
        flows.add(new ValueFlow("polygon:0xex2:0", Network.POLYGON, "0xex2", now.minusSeconds(50), watchedFailed, peer, USDT, BigInteger.TEN));
        for (int i = 0; i < 10_001; i++)
            flows.add(new ValueFlow("polygon:0xbusy" + i + ":0", Network.POLYGON, "0xbusy" + i, now.minusSeconds(40), peer, busy, USDT, BigInteger.ONE));
        chains.restoreIntelligence(flows, List.of(), List.of());
        int flowsBefore = intel.flows().size();
        int signalsBefore = intel.signals().size();

        var s = monitor.refresh();

        Map<ExemptWorkingSetMonitor.Category, ExemptWorkingSetMonitor.CategorySummary> by = s.byCategory();
        assertThat(by.get(ExemptWorkingSetMonitor.Category.WATCHLIST).addresses()).isEqualTo(1);
        assertThat(by.get(ExemptWorkingSetMonitor.Category.WATCHLIST).flows()).isEqualTo(1);
        assertThat(s.entries()).filteredOn(e -> e.address().equals(watchedFailed.value())).singleElement()
                .satisfies(e -> { assertThat(e.alsoHistory()).isTrue(); assertThat(e.historyStatus()).isEqualTo("FAILED"); });
        assertThat(s.entries()).filteredOn(e -> e.address().equals(loaded.value())).singleElement()
                .satisfies(e -> assertThat(e.category()).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_LOADED));
        assertThat(s.entries()).filteredOn(e -> e.address().equals(partial.value())).singleElement()
                .satisfies(e -> assertThat(e.category()).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_PARTIAL));
        assertThat(s.entries()).filteredOn(e -> e.address().equals(empty.value())).singleElement()
                .satisfies(e -> assertThat(e.category()).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_EMPTY));
        assertThat(s.entries()).filteredOn(e -> e.address().equals(failed.value())).singleElement()
                .satisfies(e -> assertThat(e.category()).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_FAILED));
        assertThat(s.entries()).filteredOn(e -> e.address().equals(busy.value())).singleElement().satisfies(e -> {
            assertThat(e.flowsInMemory()).isEqualTo(10_001);
            assertThat(e.overProposedPerAddressCap()).isTrue();
            assertThat(e.estimatedBytes()).isEqualTo(10_001L * ExemptWorkingSetMonitor.BYTES_PER_FLOW);
            assertThat(e.lastViewedAt()).isNull();             // not consulted since start-up
            assertThat(e.recordAgeSeconds()).isGreaterThanOrEqualTo(7_000);
        });
        assertThat(s.addressesOverProposedPerAddressCap()).isGreaterThanOrEqualTo(1);
        // the flow between two exempt addresses is counted once in the distinct total
        assertThat(s.distinctExemptFlows()).isGreaterThanOrEqualTo(10_003);
        assertThat(s.entries().stream().mapToLong(ExemptWorkingSetMonitor.Entry::flowsInMemory).sum()).isGreaterThan(s.distinctExemptFlows());

        // observation only: nothing changed in the working set
        assertThat(intel.flows().size()).isEqualTo(flowsBefore);
        assertThat(intel.signals().size()).isEqualTo(signalsBefore);

        // a consultation is recorded (observation) and the per-address detail is admin only
        Address tronWallet = chains.runtime(Network.TRON).orElseThrow().simulator().orElseThrow().addressPool().get(0);
        histories.save(progress(tronWallet, Status.COMPLETE, 7));
        mvc.perform(get("/api/v1/wallets/" + tronWallet.value() + "/risk?network=tron").header("X-API-Key", ANALYST))
                .andExpect(status().isOk());
        assertThat(monitor.refresh().entries()).filteredOn(e -> e.address().equals(tronWallet.value())).singleElement()
                .satisfies(e -> assertThat(e.viewAgeSeconds()).isNotNull().isLessThan(60));
        mvc.perform(get("/api/v1/admin/working-set/exempt")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/working-set/exempt").header("X-API-Key", READER)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/working-set/exempt").header("X-API-Key", ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].flowsInMemory").value(10_001))
                .andExpect(jsonPath("$.byCategory.WATCHLIST.addresses").value(1));
    }

    @Test void historyCategoryDistinguishesLoadedPartialInProgressEmptyAndFailed() {
        Address a = poly("f1");
        assertThat(ExemptWorkingSetMonitor.historyCategory(progress(a, Status.COMPLETE, 3))).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_LOADED);
        assertThat(ExemptWorkingSetMonitor.historyCategory(progress(a, Status.COMPLETE, 0))).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_EMPTY);
        assertThat(ExemptWorkingSetMonitor.historyCategory(progress(a, Status.TRUNCATED, 9))).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_PARTIAL);
        assertThat(ExemptWorkingSetMonitor.historyCategory(progress(a, Status.TRUNCATED, 0))).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_EMPTY);
        assertThat(ExemptWorkingSetMonitor.historyCategory(progress(a, Status.PENDING, 0))).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_IN_PROGRESS);
        assertThat(ExemptWorkingSetMonitor.historyCategory(progress(a, Status.RUNNING, 4))).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_IN_PROGRESS);
        assertThat(ExemptWorkingSetMonitor.historyCategory(progress(a, Status.FAILED, 0))).isEqualTo(ExemptWorkingSetMonitor.Category.HISTORY_FAILED);
    }
}
