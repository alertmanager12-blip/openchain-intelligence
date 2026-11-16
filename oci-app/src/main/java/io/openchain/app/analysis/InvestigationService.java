package io.openchain.app.analysis;

import io.openchain.app.api.ApiException;
import io.openchain.app.api.ApiMapper;
import io.openchain.app.api.Dto;
import io.openchain.app.chain.ChainIngestionService;
import io.openchain.app.labels.LabelCatalog;
import io.openchain.core.detection.Signal;
import io.openchain.core.model.Address;
import io.openchain.core.model.Balance;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.core.provider.BlockchainProvider;
import io.openchain.core.provider.ProviderException;
import io.openchain.core.risk.RiskLevel;
import io.openchain.core.risk.RiskScore;
import io.openchain.intelligence.IntelligencePipeline;
import io.openchain.intelligence.exposure.ExposurePaths;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.profile.WalletProfile;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Read models for investigation: wallet analysis, risk, exposure, transaction analysis and the flow graph. */
@Service
public class InvestigationService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InvestigationService.class);

    /**
     * Whether code is deployed at the address, from the network's provider (EVM eth_getCode, TRON /wallet/getcontract;
     * cached by the provider). null = unknown: no real provider (simulated chain) or lookup failure.
     */
    Boolean contractStatus(Address a) {
        try {
            return chains.runtime(a.network()).flatMap(rt -> rt.provider().isContract(a, -1)).orElse(null);
        } catch (RuntimeException e) {
            log.debug("contract lookup for {} failed: {}", a, e.getMessage());
            return null;
        }
    }

    /**
     * Known blind spots of the live data sources, so that an analyst never mistakes "no alert" for "nothing happened".
     * Simulated (demo) chains have none of them.
     */
    public List<String> limitations(Network n) {
        var rt = chains.runtime(n);
        if (rt.isEmpty() || rt.get().simulator().isPresent()) return List.of();
        List<String> out = new ArrayList<>();
        long seen = chains.observedDays(n), need = chains.ageWarmup().toDays();
        if (seen < need) {
            out.add("Address age and activity fields (wallet.age_days, wallet.tx_count, counterparty.age_days, "
                    + "approval.spender_age_days) are measured from the platform's first observation: they are withheld until "
                    + need + " days of observation (" + seen + " so far), so rules using them do not fire yet.");
        }
        if (n == Network.TRON) {
            out.add("No historical balance from TronGrid: wallet.balance is absent, so balance-based rules (wallet-draining) and "
                    + "the rapid-drain detector do not run on this network.");
            out.add("TRC-10 assets and internal transactions are not indexed.");
        } else {
            out.add("Internal transactions (value moved by contracts during execution) are not indexed: such flows are invisible "
                    + "to exposure and drain detection.");
        }
        return out;
    }

    /** Value flows known to the platform for an address (live indexing + address history), chronological. */
    public List<ValueFlow> knownFlows(Address a) { return intelligence.flows().involving(a); }

    public static final String DISCLAIMER = "Signals indicate risk indicators or anomalies, not wrongdoing.";
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9]{3,128}$");
    private static final Pattern HASH = Pattern.compile("^[A-Za-z0-9]{8,130}$");
    private static final int MAX_COUNTERPARTIES = 20;
    private static final int ACTIVITY_DAYS = 30;

    private final ChainIngestionService chains;
    private final IntelligencePipeline intelligence;
    private final LabelCatalog labels;
    private final Clock clock;
    private final io.openchain.app.history.AddressHistoryService history;

    public InvestigationService(ChainIngestionService chains, IntelligencePipeline intelligence, LabelCatalog labels, Clock clock,
                                io.openchain.app.history.AddressHistoryService history) {
        this.history = history;
        this.chains = chains;
        this.intelligence = intelligence;
        this.labels = labels;
        this.clock = clock;
    }

    // ---- parameters ---------------------------------------------------------------------------------------------

    /** Parses {@code network}; 400 when unknown, 422 when known but not enabled on this instance. */
    public Network network(String id) {
        if (id == null || id.isBlank()) throw ApiException.badRequest("query parameter 'network' is required");
        Network n;
        try {
            n = Network.fromId(id.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown network '" + id + "'");
        }
        if (chains.runtime(n).isEmpty()) {
            throw ApiException.validation("network '" + n.id() + "' is not enabled on this instance",
                    List.of(new ApiException.FieldError("/network", "unsupported_network", "network not enabled")));
        }
        return n;
    }

    public Address address(Network n, String value) {
        if (value == null || !ADDRESS.matcher(value).matches()) throw ApiException.badRequest("invalid address");
        return new Address(n, value);
    }

    public String txHash(String value) {
        if (value == null || !HASH.matcher(value).matches()) throw ApiException.badRequest("invalid transaction hash");
        return value;
    }

    private BlockchainProvider provider(Network n) {
        return chains.provider(n).orElseThrow(() -> ApiException.validation("network not enabled", List.of()));
    }

    // ---- risk / labels ------------------------------------------------------------------------------------------

    public RiskScore currentRisk(Address a) {
        return intelligence.scoring().latest(a).orElseGet(() -> intelligence.scoring().rescore(a));
    }

    public Dto.RiskScoreDto risk(Address a, boolean withHistory) {
        RiskScore r = currentRisk(a);
        List<RiskScore> history = withHistory ? intelligence.scoring().history(a) : null;
        return ApiMapper.risk(r, history, c -> evidenceLevel(a, c));
    }

    private String evidenceLevel(Address a, RiskScore.Contribution c) {
        if (c.signalId() != null) {
            Optional<Signal> s = intelligence.signals().findById(c.signalId());
            if (s.isPresent()) return s.get().evidenceLevel().name();
        }
        if (c.factor() != null && c.factor().startsWith("label:")) {
            String cat = c.factor().substring("label:".length());
            for (AddressLabel l : labels.findByAddress(a)) if (l.category().name().equals(cat)) return l.evidenceLevel().name();
        }
        return Signal.EvidenceLevel.ANOMALY.name();
    }

    private String labelText(Address a) {
        List<AddressLabel> ls = labels.findByAddress(a);
        if (ls.isEmpty()) return null;
        AddressLabel best = ls.stream().max(Comparator.comparingDouble(AddressLabel::sourceReliability)).orElseThrow();
        return best.name() != null ? best.name() : ChainIngestionService.categoryId(best.category());
    }

    // ---- wallet -------------------------------------------------------------------------------------------------

    /** Starts the on-demand history backfill of an address that is not covered yet (ADR-0020); no-op otherwise. */
    public void ensureHistory(Address a) {
        try {
            history.ensure(a);
        } catch (RuntimeException e) { // never fail an analysis because of the history store
            org.slf4j.LoggerFactory.getLogger(InvestigationService.class).warn("address history of {} not started: {}", a, e.getMessage());
        }
    }

    public Dto.WalletAnalysisDto wallet(Address a) {
        ensureHistory(a);
        WalletProfile p = intelligence.profiles().profileOrEmpty(a);
        Dto.RiskScoreDto risk = risk(a, true);
        List<Dto.BalanceDto> balances = new ArrayList<>();
        String freshness = "FRESH";
        try {
            for (Balance b : provider(a.network()).getAddressBalance(a)) {
                balances.add(new Dto.BalanceDto(ApiMapper.token(b.token()), ApiMapper.raw(b.rawAmount()),
                        ApiMapper.amount(b.rawAmount(), b.token().decimals()), b.atBlock(), b.observedAt()));
            }
        } catch (ProviderException e) {
            freshness = "STALE"; // indexed data only; live balances unavailable
        }
        List<ValueFlow> flows = intelligence.flows().involving(a);
        Map<String, Token> tokenByKey = new LinkedHashMap<>();
        for (ValueFlow f : flows) tokenByKey.putIfAbsent(f.tokenKey(), f.token());
        List<Dto.FlowDto> flowDtos = new ArrayList<>();
        p.tokens().forEach((key, st) -> {
            Token t = tokenByKey.getOrDefault(key, new Token(a.network(), null, st.symbol(), st.decimals()));
            flowDtos.add(new Dto.FlowDto(ApiMapper.token(t), ApiMapper.raw(st.inflowRaw()), ApiMapper.raw(st.outflowRaw())));
        });
        List<Dto.SignalDto> signals = intelligence.signals().findByAddress(a).stream()
                .sorted(Comparator.comparing(Signal::detectedAt).reversed()).map(ApiMapper::signal).toList();
        Boolean contract = contractStatus(a);
        Dto.ProfileDto profile = new Dto.ProfileDto(p.firstActivity(), p.lastActivity(), p.transactionCount(), contract,
                p.inboundTransfers(), p.outboundTransfers(), ApiMapper.round(p.txPerDay()));
        return new Dto.WalletAnalysisDto(a.value(), a.network().id(), p.firstActivity(), p.lastActivity(), p.transactionCount(),
                contract, labels.findByAddress(a).stream().map(ApiMapper::label).toList(), balances, flowDtos,
                counterparties(a, flows), activity(a, flows), signals, risk, profile, risk, freshness, DISCLAIMER,
                history.coverage(a), limitations(a.network()));
    }

    private List<Dto.CounterpartyDto> counterparties(Address self, List<ValueFlow> flows) {
        record Acc(Address cp, Map<String, BigInteger[]> byToken, Map<String, Token> tokens, int[] counts) {}
        Map<Address, Acc> acc = new LinkedHashMap<>();
        for (ValueFlow f : flows) {
            if (f.from().equals(f.to())) continue;
            Address cp = f.counterparty(self);
            Acc x = acc.computeIfAbsent(cp, k -> new Acc(k, new HashMap<>(), new HashMap<>(), new int[3]));
            BigInteger[] io = x.byToken().computeIfAbsent(f.tokenKey(), k -> new BigInteger[]{BigInteger.ZERO, BigInteger.ZERO});
            x.tokens().putIfAbsent(f.tokenKey(), f.token());
            if (f.to().equals(self)) { io[0] = io[0].add(f.rawAmount()); x.counts()[0]++; }
            else { io[1] = io[1].add(f.rawAmount()); x.counts()[1]++; }
        }
        return acc.values().stream()
                .sorted(Comparator.comparingInt((Acc x) -> x.counts()[0] + x.counts()[1]).reversed().thenComparing(x -> x.cp().value()))
                .limit(MAX_COUNTERPARTIES)
                .map(x -> {
                    // the dominant token (largest raw volume) represents the relationship
                    String key = x.byToken().entrySet().stream()
                            .max(Comparator.comparing(e -> e.getValue()[0].add(e.getValue()[1]))).orElseThrow().getKey();
                    BigInteger[] io = x.byToken().get(key);
                    int in = x.counts()[0], out = x.counts()[1];
                    String dir = in > 0 && out > 0 ? "BOTH" : in > 0 ? "IN" : "OUT";
                    RiskLevel lvl = intelligence.scoring().latest(x.cp()).map(RiskScore::level).orElse(RiskLevel.UNKNOWN);
                    return new Dto.CounterpartyDto(x.cp().value(), labelText(x.cp()), dir, in + out, ApiMapper.token(x.tokens().get(key)),
                            io[0].toString(), io[1].toString(), lvl.name());
                }).toList();
    }

    private List<Dto.ActivityDto> activity(Address self, List<ValueFlow> flows) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        Map<LocalDate, int[]> days = new LinkedHashMap<>();
        for (int i = ACTIVITY_DAYS - 1; i >= 0; i--) days.put(today.minusDays(i), new int[2]);
        for (ValueFlow f : flows) {
            int[] d = days.get(LocalDate.ofInstant(f.timestamp(), ZoneOffset.UTC));
            if (d == null) continue;
            if (f.to().equals(self)) d[0]++;
            if (f.from().equals(self)) d[1]++;
        }
        List<Dto.ActivityDto> out = new ArrayList<>();
        days.forEach((day, c) -> out.add(new Dto.ActivityDto(day.toString(), c[0], c[1])));
        return out;
    }

    // ---- exposure -----------------------------------------------------------------------------------------------

    /**
     * Exposure to addresses listed in a risky category (Fund Flow Trace P0-A, docs/FUND_FLOW_TRACE_P0_A_EXPOSURE.md).
     * Paths are qualified by {@link ExposurePaths}, the same rules as the exposure detector: unverified tokens and inbound
     * dust create no exposure (listed in {@code ignored}), upstream flows must not be later than the downstream one,
     * tracing stops at the first listed address and at exchanges and bridges. Amounts are exact and per token, never
     * added across tokens. Non-risky labelled counterparties are listed apart. The data is the in-memory working set
     * plus the address history when loaded: the coverage is reported as PARTIAL, never complete.
     */
    public Dto.ExposureDto exposure(Address a, int depth) {
        var cfg = ExposurePaths.withMaxHops(intelligence.detectionConfig().exposure(), depth);
        List<ValueFlow> involving = intelligence.flows().involving(a);
        LegacyShare legacy = new LegacyShare(a, involving);

        List<Dto.ExposureEntryDto> direct = new ArrayList<>();
        List<Dto.IgnoredTransferDto> ignored = new ArrayList<>();
        for (ExposurePaths.Direct d : ExposurePaths.direct(a, intelligence.flows(), labels, cfg)) {
            AddressLabel l = d.label();
            String cat = ChainIngestionService.categoryId(l.category());
            for (ValueFlow f : d.ignored()) {
                ignored.add(new Dto.IgnoredTransferDto(l.address().value(), cat, l.source(),
                        f.token().isUnverified() ? "unverified_token" : "below_dust_threshold", f.token().symbol(),
                        f.amount().stripTrailingZeros().toPlainString(), f.txHash(), f.timestamp()));
            }
            if (!d.exposed()) continue;
            Map<String, BigDecimal[]> perToken = new java.util.TreeMap<>();
            for (ValueFlow f : d.flows()) {
                BigDecimal[] io = perToken.computeIfAbsent(f.token().symbol(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                if (f.to().equals(a)) io[0] = io[0].add(f.amount()); else io[1] = io[1].add(f.amount());
            }
            List<Dto.TokenFlowDto> amounts = new ArrayList<>();
            perToken.forEach((sym, io) -> amounts.add(new Dto.TokenFlowDto(sym, plain(io[0]), plain(io[1]))));
            List<String> hashes = d.flows().reversed().stream().map(ValueFlow::txHash).distinct().limit(MAX_HASHES).toList();
            direct.add(new Dto.ExposureEntryDto(cat, 1, legacy.direct(l.address()), l.address().value(), l.evidenceLevel().name(),
                    l.source(), amounts, d.flows().size(), hashes, null, null, l.sourceReliability()));
        }

        List<Dto.ExposureEntryDto> indirect = new ArrayList<>();
        for (ExposurePaths.Indirect i : ExposurePaths.indirect(a, intelligence.flows(), labels, cfg)) {
            AddressLabel l = i.label();
            ValueFlow b = i.bottleneck();
            List<Dto.PathHopDto> path = i.path().stream().map(f -> new Dto.PathHopDto(f.from().value(), f.to().value(), f.txHash(),
                    f.token().symbol(), plain(f.amount()), f.timestamp())).toList();
            // an inference: an indirect path is never more than a risk signal, whatever the label's own level
            indirect.add(new Dto.ExposureEntryDto(ChainIngestionService.categoryId(l.category()), i.hops(), legacy.path(i.path()),
                    l.address().value(), Signal.EvidenceLevel.RISK_SIGNAL.name(), l.source(), null, null,
                    i.path().stream().map(ValueFlow::txHash).toList(), path,
                    new Dto.TokenAmountDto(b.token().symbol(), plain(b.amount())), Math.round(i.confidence() * 10_000) / 10_000.0));
        }

        Map<String, Dto.LabelledCounterpartyDto> others = new LinkedHashMap<>();
        Map<String, Integer> otherCounts = new HashMap<>();
        for (ValueFlow f : involving) {
            if (f.from().equals(f.to())) continue;
            for (AddressLabel l : labels.findByAddress(f.counterparty(a))) {
                if (l.category().risky()) continue;
                String key = l.key();
                otherCounts.merge(key, 1, Integer::sum);
                others.putIfAbsent(key, new Dto.LabelledCounterpartyDto(l.address().value(), ChainIngestionService.categoryId(l.category()),
                        l.source(), l.evidenceLevel().name(), 0));
            }
        }
        List<Dto.LabelledCounterpartyDto> counterparties = others.entrySet().stream().map(e -> new Dto.LabelledCounterpartyDto(
                e.getValue().counterparty(), e.getValue().category(), e.getValue().source(), e.getValue().evidenceLevel(),
                otherCounts.get(e.getKey()))).toList();

        return new Dto.ExposureDto(a.value(), a.network().id(), depth, direct, indirect, ignored, counterparties, coverage(a, involving));
    }

    static final int MAX_HASHES = 20;

    /** PARTIAL until P0-B: says which data the answer rests on, so that "none found" is never read as "none at all". */
    private Dto.CoverageDto coverage(Address a, List<ValueFlow> involving) {
        List<String> limits = new ArrayList<>();
        limits.add("Based on the flows held in memory (bounded working set, kept for a limited time) and on the address history "
                + "when it was loaded; earlier activity may be missing. No exposure here means none in these data, not none at all.");
        Dto.HistoryCoverageDto h = history.coverage(a);
        limits.add("Address history: " + h.status() + (h.message() == null ? "" : " - " + h.message()));
        limits.addAll(limitations(a.network()));
        Instant from = involving.isEmpty() ? null : involving.getFirst().timestamp();
        Instant to = involving.isEmpty() ? null : involving.getLast().timestamp();
        return new Dto.CoverageDto("PARTIAL", from, to, limits);
    }

    private static String plain(BigDecimal v) { return v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString(); }

    /**
     * The deprecated {@code share} of the v1 contract, computed as before (share of the volume summed over all tokens,
     * no unit) so that existing consumers see no silent change of meaning (decision D6, option b).
     */
    private final class LegacyShare {
        private final Map<Address, BigDecimal> volume = new HashMap<>();
        private BigDecimal total = BigDecimal.ZERO;

        LegacyShare(Address a, List<ValueFlow> involving) {
            for (ValueFlow f : involving) {
                if (f.from().equals(f.to())) continue;
                volume.merge(f.counterparty(a), f.amount(), BigDecimal::add);
                total = total.add(f.amount());
            }
        }

        double direct(Address counterparty) { return round(share(volume.getOrDefault(counterparty, BigDecimal.ZERO), total)); }

        /** Product, along the path, of the sender's share of the receiver's inbound volume. */
        double path(List<ValueFlow> path) {
            double s = 1.0;
            for (ValueFlow f : path) {
                BigDecimal fromSender = BigDecimal.ZERO, all = BigDecimal.ZERO;
                for (ValueFlow g : intelligence.flows().incoming(f.to())) {
                    if (g.from().equals(f.to())) continue;
                    all = all.add(g.amount());
                    if (g.from().equals(f.from())) fromSender = fromSender.add(g.amount());
                }
                s *= share(fromSender, all);
            }
            return round(s);
        }

        private double round(double v) { return Math.round(v * 10_000) / 10_000.0; }
    }

    private static double share(BigDecimal part, BigDecimal total) {
        if (total.signum() == 0) return 0;
        return part.divide(total, MathContext.DECIMAL64).doubleValue();
    }

    // ---- transactions -------------------------------------------------------------------------------------------

    public Dto.TxAnalysisDto transaction(Network n, String hash) {
        BlockchainProvider p = provider(n);
        BlockchainProvider.TransactionReceipt r = p.getTransactionReceipt(hash)
                .orElseThrow(() -> ApiException.notFound("transaction " + hash + " not found on " + n.id()));
        long head = chains.runtime(n).map(chains::status).map(s -> s.headBlock()).orElse(r.transaction().blockNumber());
        long confirmations = Math.max(0, head - r.transaction().blockNumber());
        Set<Address> parties = new java.util.LinkedHashSet<>();
        if (r.transaction().from() != null) parties.add(r.transaction().from());
        r.transfers().forEach(t -> { parties.add(t.from()); parties.add(t.to()); });
        List<Dto.SignalDto> signals = new ArrayList<>();
        List<Dto.CounterpartyRiskDto> cps = new ArrayList<>();
        for (Address a : parties) {
            intelligence.signals().findByAddress(a).stream().filter(s -> hash.equals(s.txHash())).map(ApiMapper::signal).forEach(signals::add);
            Optional<RiskScore> s = intelligence.scoring().latest(a);
            cps.add(new Dto.CounterpartyRiskDto(a.value(), s.map(RiskScore::score).orElse(0), s.map(x -> x.level().name()).orElse("UNKNOWN"),
                    labels.findByAddress(a).stream().map(l -> ChainIngestionService.categoryId(l.category())).distinct().toList()));
        }
        return new Dto.TxAnalysisDto(ApiMapper.tx(r.transaction()), r.transfers().stream().map(ApiMapper::transfer).toList(), signals,
                confirmations, confirmations >= n.defaultConfirmations(), cps);
    }

    // ---- graph --------------------------------------------------------------------------------------------------

    /** BFS over indexed value flows; edges aggregated per (source, target, token). */
    public Dto.GraphDto graph(Address root, int depth, String tokenFilter, Instant from, Instant to, int maxNodes) {
        Map<Address, Integer> nodeDepth = new LinkedHashMap<>();
        nodeDepth.put(root, 0);
        record EdgeKey(Address source, Address target, String token) {}
        final class EdgeAcc { Token token; BigInteger raw = BigInteger.ZERO; int count; String lastTx; Instant last; }
        Map<EdgeKey, EdgeAcc> edges = new LinkedHashMap<>();
        Set<String> counted = new HashSet<>(); // a flow between two visited nodes is reached from both ends
        boolean truncated = false;
        List<Address> frontier = List.of(root);
        for (int d = 1; d <= depth && !frontier.isEmpty(); d++) {
            List<Address> next = new ArrayList<>();
            for (Address src : frontier) {
                for (ValueFlow f : intelligence.flows().involving(src)) {
                    if (tokenFilter != null && !tokenFilter.isBlank() && !tokenFilter.equalsIgnoreCase(f.token().symbol())
                            && !tokenFilter.equalsIgnoreCase(f.token().contract())) continue;
                    if (from != null && f.timestamp().isBefore(from)) continue;
                    if (to != null && f.timestamp().isAfter(to)) continue;
                    Address other = f.counterparty(src);
                    if (!nodeDepth.containsKey(other)) {
                        if (nodeDepth.size() >= maxNodes) { truncated = true; continue; }
                        nodeDepth.put(other, d);
                        next.add(other);
                    }
                    if (!counted.add(f.eventId())) continue;
                    EdgeAcc e = edges.computeIfAbsent(new EdgeKey(f.from(), f.to(), f.tokenKey()), k -> new EdgeAcc());
                    e.token = f.token();
                    e.raw = e.raw.add(f.rawAmount());
                    e.count++;
                    if (e.last == null || f.timestamp().isAfter(e.last)) { e.last = f.timestamp(); e.lastTx = f.txHash(); }
                }
            }
            frontier = next;
        }
        List<Dto.GraphNodeDto> nodes = new ArrayList<>();
        nodeDepth.forEach((a, d) -> {
            Optional<RiskScore> s = intelligence.scoring().latest(a);
            List<AddressLabel> ls = labels.findByAddress(a);
            nodes.add(new Dto.GraphNodeDto(a.value(), a.value(), a.network().id(), labelText(a),
                    ls.stream().map(l -> ChainIngestionService.categoryId(l.category())).distinct().toList(),
                    s.map(x -> x.level().name()).orElse("UNKNOWN"), s.map(RiskScore::score).orElse(0), null, d));
        });
        List<Dto.GraphEdgeDto> edgeDtos = new ArrayList<>();
        int i = 0;
        for (Map.Entry<EdgeKey, EdgeAcc> e : edges.entrySet()) {
            if (!nodeDepth.containsKey(e.getKey().source()) || !nodeDepth.containsKey(e.getKey().target())) continue;
            EdgeAcc x = e.getValue();
            edgeDtos.add(new Dto.GraphEdgeDto("e" + (i++), e.getKey().source().value(), e.getKey().target().value(), x.lastTx,
                    ApiMapper.token(x.token), x.raw.toString(), ApiMapper.amount(x.raw, x.token.decimals()), x.last, x.count));
        }
        return new Dto.GraphDto(root.value(), root.network().id(), nodes, edgeDtos, truncated);
    }
}
