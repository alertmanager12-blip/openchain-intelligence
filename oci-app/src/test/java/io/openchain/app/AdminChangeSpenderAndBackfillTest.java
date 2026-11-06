package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openchain.alerting.pipeline.AlertingPipeline;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.chain.FreezeBackfillService;
import io.openchain.blockchain.backfill.FreezeBackfill;
import io.openchain.blockchain.backfill.FreezeLogScanner;
import io.openchain.blockchain.indexer.IndexerConfig;
import io.openchain.core.detection.SignalType;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.*;
import io.openchain.core.provider.BlockchainProvider;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/**
 * An Ethereum network with a scripted provider (ETH-shaped addresses, registry USDT) registered next to the demo TRON chain:
 * <ul>
 *   <li>approval spender enrichment ({@code approval.spender_is_contract} from the provider's code lookup,
 *       {@code approval.spender_age_days} from the platform's first sighting) makes the repository rule
 *       {@code rules/ethereum/suspicious-approval.yaml} fire on an unlimited approval to a freshly seen contract, and NOT
 *       for an EOA spender nor for Permit2;</li>
 *   <li>an {@code OwnershipTransferred} of the registry USDT contract becomes a CONTRACT_ADMIN_CHANGE and an
 *       OWNERSHIP_CHANGE signal; the same event on an unwatched contract is not ingested;</li>
 *   <li>the freeze backfill endpoint is admin-only, labels the historical frozen address without raising alerts and is
 *       idempotent.</li>
 * </ul>
 * The context is discarded afterwards (an extra network is registered).
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminChangeSpenderAndBackfillTest extends ApiTestSupport {

    static final Network ETH = Network.ETHEREUM;
    static final Token USDT = new Token(ETH, "0xdac17f958d2ee523a2206206994597c13d831ec7", "USDT", 6);
    static final Address FUNDER = eth("f0"), OWNER = eth("a1"), OWNER2 = eth("a2"), OWNER3 = eth("a3");
    static final Address DRAINER = eth("dd"), EOA_SPENDER = eth("ee");
    static final Address PERMIT2 = new Address(ETH, "0x000000000022d473030f116ddee9f6b43ac78ba3");
    static final Address NEW_ADMIN = eth("ad"), OLD_ADMIN = eth("ac"), OTHER_CONTRACT = eth("cc"), FROZEN = eth("fe");

    static Address eth(String b) { return new Address(ETH, "0x" + b.repeat(20)); }

    /** Scripted chain: fixed blocks, contract set, finalized = head; also the freeze backfill scanner. */
    static final class ScriptedEthereum implements BlockchainProvider, FreezeLogScanner {
        final Map<Long, BlockActivity> blocks = new HashMap<>();
        final Set<Address> contracts = Set.of(DRAINER, PERMIT2, OTHER_CONTRACT, new Address(ETH, USDT.contract()));
        final List<TokenFreeze> history = new ArrayList<>();
        final AtomicInteger codeLookups = new AtomicInteger(), scans = new AtomicInteger();
        final Instant t0 = Instant.now().minus(Duration.ofMinutes(10));
        long head = 6;

        static String hash(long n) { return "0x" + "%064x".formatted(n + 0xabc000L); }
        Instant ts(long n) { return t0.plusSeconds(12 * n); }
        BlockRef ref(long n) { return new BlockRef(ETH, n, hash(n), hash(n - 1), ts(n)); }

        @Override public String id() { return "scripted-eth"; }
        @Override public Network network() { return ETH; }
        @Override public Optional<BlockRef> getBlock(long n) { return n >= 0 && n <= head ? Optional.of(ref(n)) : Optional.empty(); }
        @Override public Optional<ChainTransaction> getTransaction(String h) { return Optional.empty(); }
        @Override public Optional<TransactionReceipt> getTransactionReceipt(String h) { return Optional.empty(); }
        @Override public Page<ChainTransaction> getAddressTransactions(Address a, String c, int l) { return new Page<>(List.of(), null); }
        @Override public List<TokenTransfer> getTokenTransfers(long f, long t) { return getBlockActivity(f, t).tokenTransfers(); }
        @Override public BlockActivity getBlockActivity(long from, long to) {
            List<TokenTransfer> tt = new ArrayList<>();
            List<TokenApproval> ap = new ArrayList<>();
            List<ContractAdminChange> ad = new ArrayList<>();
            for (long n = from; n <= to; n++) {
                BlockActivity a = blocks.getOrDefault(n, BlockActivity.EMPTY);
                tt.addAll(a.tokenTransfers());
                ap.addAll(a.approvals());
                ad.addAll(a.adminChanges());
            }
            return new BlockActivity(tt, List.of(), ap, List.of(), ad);
        }
        @Override public Optional<Boolean> isContract(Address a, long atBlock) {
            codeLookups.incrementAndGet();
            return Optional.of(contracts.contains(a));
        }
        @Override public List<Balance> getAddressBalance(Address a) { return List.of(); }
        @Override public Subscription subscribeToEvents(Consumer<BlockRef> onNewHead) { return () -> { }; }
        @Override public NetworkStatus getNetworkStatus() {
            return new NetworkStatus(ETH, head, head, true, id(), 1, Instant.now(), "ok");
        }
        @Override public long backfillSafeHead() { return 1_000; }
        @Override public List<TokenFreeze> scanFreezes(long from, long to) {
            scans.incrementAndGet();
            return history.stream().filter(z -> z.blockNumber() >= from && z.blockNumber() <= to).toList();
        }

        TokenTransfer transfer(long n, int li, Address from, Address to, long units) {
            return new TokenTransfer(ETH, "0x" + "%064x".formatted(n * 100 + li), li, n, hash(n), ts(n), from, to, USDT,
                    BigInteger.valueOf(units).multiply(BigInteger.TEN.pow(6)));
        }
        TokenApproval approval(long n, int li, Address owner, Address spender) {
            return new TokenApproval(ETH, "0x" + "%064x".formatted(n * 100 + li), li, n, hash(n), ts(n), owner, spender, USDT,
                    TokenApproval.MAX_UINT256);
        }
    }

    @Autowired ChainIngestionService chains;
    @Autowired FreezeBackfillService backfills;
    @Autowired EventHistory history;
    @Autowired AlertingPipeline alerting;

    static ScriptedEthereum eth;

    @BeforeEach
    synchronized void registerOnce() {
        if (chains.runtime(ETH).isPresent()) return;
        eth = new ScriptedEthereum();
        eth.blocks.put(1L, BlockActivity.ofTransfers(List.of(
                eth.transfer(1, 0, FUNDER, OWNER, 50_000), eth.transfer(1, 1, FUNDER, OWNER2, 50_000), eth.transfer(1, 2, FUNDER, OWNER3, 50_000),
                eth.transfer(1, 3, FUNDER, DRAINER, 1), eth.transfer(1, 4, FUNDER, EOA_SPENDER, 1), eth.transfer(1, 5, FUNDER, PERMIT2, 1))));
        eth.blocks.put(2L, new BlockActivity(List.of(), List.of(), List.of(eth.approval(2, 0, OWNER, DRAINER),
                eth.approval(2, 1, OWNER2, EOA_SPENDER), eth.approval(2, 2, OWNER3, PERMIT2)), List.of()));
        eth.blocks.put(3L, new BlockActivity(List.of(), List.of(), List.of(), List.of(), List.of(
                new ContractAdminChange(ETH, "0x" + "%064x".formatted(301), 0, 3, ScriptedEthereum.hash(3), eth.ts(3),
                        new Address(ETH, USDT.contract()), ContractAdminChange.ROLE_OWNER, OLD_ADMIN, NEW_ADMIN, "OwnershipTransferred"),
                new ContractAdminChange(ETH, "0x" + "%064x".formatted(302), 1, 3, ScriptedEthereum.hash(3), eth.ts(3),
                        OTHER_CONTRACT, ContractAdminChange.ROLE_OWNER, OLD_ADMIN, NEW_ADMIN, "OwnershipTransferred"))));
        eth.history.add(new TokenFreeze(ETH, "0x" + "%064x".formatted(777), 4, 500, "0x" + "%064x".formatted(500),
                eth.t0.minus(Duration.ofDays(30)), FROZEN, USDT, "tether", TokenFreeze.Action.FREEZE, "AddedBlackList"));
        ChainIngestionService.NetworkRuntime rt = chains.register(eth, null, null, new IndexerConfig("test-eth", 1, 0, 10, 100), null);
        chains.poll(rt);
        alerting.dispatch(100);
        backfills.register(eth, new FreezeBackfill.Config(100, null, 300, 10, Duration.ZERO));
    }

    private List<ChainEvent> ethEvents() { return history.range(null, null, ETH, 100_000); }

    @Test
    void approvalSpenderFieldsMakeSuspiciousApprovalFireOnlyForTheYoungContractSpender() throws Exception {
        ChainEvent drainer = ethEvents().stream().filter(e -> e.type() == ChainEvent.EventType.APPROVAL
                && DRAINER.value().equals(e.field("approval.spender"))).findFirst().orElseThrow();
        assertThat(drainer.field("approval.spender_is_contract")).isEqualTo(true);
        assertThat(drainer.field("approval.spender_age_days")).isEqualTo(0L); // first seen 12 s earlier (block 1)
        assertThat(drainer.field("approval.spender_verified")).isNull(); // no defensible source: never invented
        ChainEvent eoa = ethEvents().stream().filter(e -> e.type() == ChainEvent.EventType.APPROVAL
                && EOA_SPENDER.value().equals(e.field("approval.spender"))).findFirst().orElseThrow();
        assertThat(eoa.field("approval.spender_is_contract")).isEqualTo(false);
        assertThat(eth.codeLookups.get()).isEqualTo(3);

        mvc.perform(get("/api/v1/alerts?ruleId=suspicious-approval").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].severity").value("HIGH"));
        String list = mvc.perform(get("/api/v1/alerts?ruleId=suspicious-approval").header("X-API-Key", READER))
                .andReturn().getResponse().getContentAsString();
        String alertId = com.jayway.jsonpath.JsonPath.read(list, "$.items[0].id");
        mvc.perform(get("/api/v1/alerts/" + alertId).header("X-API-Key", READER))
                .andExpect(jsonPath("$.matchedEvent['approval.spender']").value(DRAINER.value()))
                .andExpect(jsonPath("$.matchedEvent['approval.owner']").value(OWNER.value()));
    }

    @Test
    void ownershipTransferOfARegistryContractIsIngestedAndDetected() throws Exception {
        List<ChainEvent> admin = ethEvents().stream().filter(e -> e.type() == ChainEvent.EventType.CONTRACT_ADMIN_CHANGE).toList();
        assertThat(admin).singleElement().satisfies(e -> {
            assertThat(e.field("contract.address")).isEqualTo(USDT.contract());
            assertThat(e.field("contract.event")).isEqualTo("OwnershipTransferred");
            assertThat(e.field("contract.new_owner")).isEqualTo(NEW_ADMIN.value());
            assertThat(e.field("admin.previous")).isEqualTo(OLD_ADMIN.value());
        });
        assertThat(chains.recentSignals()).anyMatch(s -> s.type() == SignalType.OWNERSHIP_CHANGE
                && s.address().value().equals(USDT.contract()));
        assertThat(ethEvents()).anyMatch(e -> e.type() == ChainEvent.EventType.DETECTION_SIGNAL
                && "OWNERSHIP_CHANGE".equals(e.field("signal.type")));
        mvc.perform(get("/api/v1/rules/fields").header("X-API-Key", READER))
                .andExpect(jsonPath("$[?(@.path == 'contract.address')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'contract.new_owner')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'contract.verified')].available", hasItem(false)))
                .andExpect(jsonPath("$[?(@.path == 'approval.spender_is_contract')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'approval.spender_age_days')].available", hasItem(true)))
                .andExpect(jsonPath("$[?(@.path == 'approval.spender_verified')].available", hasItem(false)));
        mvc.perform(get("/api/v1/rules/event-types").header("X-API-Key", READER))
                .andExpect(jsonPath("$[?(@.type == 'CONTRACT_ADMIN_CHANGE')].available", hasItem(true)));
    }

    @Test
    void freezeBackfillEndpointIsAdminOnlyLabelsWithoutAlertsAndIsIdempotent() throws Exception {
        mvc.perform(post("/api/v1/admin/backfill/freezes?network=ethereum").header("X-API-Key", ANALYST)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/backfill/freezes").header("X-API-Key", READER)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/backfill/freezes?network=bsc").header("X-API-Key", ADMIN)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/backfill/freezes?network=ethereum").header("X-API-Key", ADMIN))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.items[0].network").value("ethereum"));
        awaitDone();
        mvc.perform(get("/api/v1/admin/backfill/freezes").header("X-API-Key", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].state").value("COMPLETED"))
                .andExpect(jsonPath("$.items[0].lastScannedBlock").value(1000))
                .andExpect(jsonPath("$.items[0].freezesFound").value(1))
                .andExpect(jsonPath("$.items[0].lastResult.chunks").value(4)); // 100..399, 400..699, 700..999, 1000..1000
        mvc.perform(get("/api/v1/wallets/" + FROZEN.value() + "/analysis?network=ethereum").header("X-API-Key", READER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[*].source", hasItem("tether-onchain")))
                .andExpect(jsonPath("$.labels[*].category", hasItem("issuer-frozen")));
        assertThat(ethEvents()).noneMatch(e -> e.type() == ChainEvent.EventType.STABLECOIN_FREEZE); // historical: no rule evaluation
        mvc.perform(get("/api/v1/alerts?ruleId=issuer-freeze-counterparty").header("X-API-Key", READER))
                .andExpect(jsonPath("$.items[*].matchedEvent['freeze.address']", not(hasItem(FROZEN.value()))));

        long applied = chains.backfilledFreezesApplied();
        int scans = eth.scans.get();
        mvc.perform(post("/api/v1/admin/backfill/freezes").header("X-API-Key", ADMIN)).andExpect(status().isAccepted());
        awaitDone();
        assertThat(eth.scans.get()).as("nothing left to scan").isEqualTo(scans);
        chains.applyBackfilledFreeze(eth.history.getFirst()); // same event again (e.g. live + backfill overlap)
        assertThat(chains.backfilledFreezesApplied()).isEqualTo(applied);
    }

    private void awaitDone() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            var s = backfills.status(ETH).orElseThrow().state();
            if (s != FreezeBackfillService.State.QUEUED && s != FreezeBackfillService.State.RUNNING) return;
            Thread.sleep(25);
        }
        throw new AssertionError("backfill did not finish");
    }
}
