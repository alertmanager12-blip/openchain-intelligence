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
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.profile.WalletProfile;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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

    public static final String DISCLAIMER = "Signals indicate risk indicators or anomalies, not wrongdoing.";
    private static final Pattern ADDRESS = Pattern.compile("^[A-Za-z0-9]{3,128}$");
    private static final Pattern HASH = Pattern.compile("^[A-Za-z0-9]{8,130}$");
    private static final int MAX_COUNTERPARTIES = 20;
    private static final int ACTIVITY_DAYS = 30;

    private final ChainIngestionService chains;
    private final IntelligencePipeline intelligence;
    private final LabelCatalog labels;
    private final Clock clock;

    public InvestigationService(ChainIngestionService chains, IntelligencePipeline intelligence, LabelCatalog labels, Clock clock) {
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

    public Dto.WalletAnalysisDto wallet(Address a) {
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
        Dto.ProfileDto profile = new Dto.ProfileDto(p.firstActivity(), p.lastActivity(), p.transactionCount(), false,
                p.inboundTransfers(), p.outboundTransfers(), ApiMapper.round(p.txPerDay()));
        return new Dto.WalletAnalysisDto(a.value(), a.network().id(), p.firstActivity(), p.lastActivity(), p.transactionCount(),
                false, labels.findByAddress(a).stream().map(ApiMapper::label).toList(), balances, flowDtos,
                counterparties(a, flows), activity(a, flows), signals, risk, profile, risk, freshness, DISCLAIMER);
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
     * Exposure to risky labelled categories through actual value flows (both directions for hop 1, inbound only
     * beyond). {@code share} = share of the wallet's volume (hop 1) or the product of inbound shares along the path.
     */
    public Dto.ExposureDto exposure(Address a, int depth) {
        List<Dto.ExposureEntryDto> direct = new ArrayList<>(), indirect = new ArrayList<>();
        Map<Address, BigDecimal> volume = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ValueFlow f : intelligence.flows().involving(a)) {
            if (f.from().equals(f.to())) continue;
            volume.merge(f.counterparty(a), f.amount(), BigDecimal::add);
            total = total.add(f.amount());
        }
        for (Map.Entry<Address, BigDecimal> e : volume.entrySet()) {
            for (AddressLabel l : labels.findByAddress(e.getKey())) {
                direct.add(entry(l, 1, share(e.getValue(), total)));
            }
        }
        // backwards BFS over inbound flows for hops 2..depth
        record Node(Address address, int hops, double share) {}
        Deque<Node> queue = new ArrayDeque<>();
        Set<Address> seen = new HashSet<>(Set.of(a));
        queue.add(new Node(a, 0, 1.0));
        int visited = 0;
        while (!queue.isEmpty() && visited < 5_000) {
            Node n = queue.poll();
            if (n.hops() >= depth) continue;
            Map<Address, BigDecimal> in = new HashMap<>();
            BigDecimal inTotal = BigDecimal.ZERO;
            for (ValueFlow f : intelligence.flows().incoming(n.address())) {
                if (f.from().equals(n.address())) continue;
                in.merge(f.from(), f.amount(), BigDecimal::add);
                inTotal = inTotal.add(f.amount());
                visited++;
            }
            for (Map.Entry<Address, BigDecimal> e : in.entrySet()) {
                double s = n.share() * share(e.getValue(), inTotal);
                int hops = n.hops() + 1;
                if (hops >= 2) for (AddressLabel l : labels.findByAddress(e.getKey())) {
                    if (l.category().risky()) indirect.add(entry(l, hops, s));
                }
                boolean stop = labels.findByAddress(e.getKey()).stream()
                        .anyMatch(l -> l.category() == LabelCategory.EXCHANGE || l.category() == LabelCategory.BRIDGE);
                if (!stop && seen.add(e.getKey()) && s >= 0.001) queue.add(new Node(e.getKey(), hops, s));
            }
        }
        indirect.sort(Comparator.comparingDouble(Dto.ExposureEntryDto::share).reversed());
        direct.sort(Comparator.comparingDouble(Dto.ExposureEntryDto::share).reversed());
        return new Dto.ExposureDto(a.value(), a.network().id(), depth, direct, indirect.size() > 50 ? indirect.subList(0, 50) : indirect);
    }

    private static Dto.ExposureEntryDto entry(AddressLabel l, int hops, double share) {
        return new Dto.ExposureEntryDto(ChainIngestionService.categoryId(l.category()), hops, Math.round(share * 10_000) / 10_000.0,
                l.address().value(), l.evidenceLevel().name(), l.source());
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
                    s.map(x -> x.level().name()).orElse("UNKNOWN"), s.map(RiskScore::score).orElse(0), false, d));
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
