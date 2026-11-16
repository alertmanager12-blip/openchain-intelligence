package io.openchain.intelligence.trace;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Contract of the fund flow trace engine (Fund Flow Trace P0-D, {@code docs/FUND_FLOW_TRACE_P0_D_ENGINE.md}). Pure data:
 * the engine reads flows only through {@link Source}, has no clock and no I/O, and gives the same result for the same
 * request and the same flows.
 */
public final class Trace {
    private Trace() {}

    public static final String ENGINE_VERSION = "trace@1";

    public enum Direction { FORWARD, BACKWARD }

    /** How value leaving an address is split among the value that entered it. Conventions, not facts. */
    public enum Method { PROPORTIONAL, FIFO }

    /** Inclusive time window; null bound = open. */
    public record Window(Instant from, Instant to) {
        public static final Window ALL = new Window(null, null);
        boolean contains(Instant t) { return (from == null || !t.isBefore(from)) && (to == null || !t.isAfter(to)); }
    }

    public record Limits(int maxDepth, int maxNodes, int maxFlowsRead) {
        public static final int DEPTH_CAP = 8, NODES_CAP = 5_000, FLOWS_CAP = 200_000;
        public static final Limits DEFAULTS = new Limits(3, 500, 20_000);
        public Limits {
            if (maxDepth < 1 || maxDepth > DEPTH_CAP) throw new IllegalArgumentException("maxDepth must be 1.." + DEPTH_CAP);
            if (maxNodes < 1 || maxNodes > NODES_CAP) throw new IllegalArgumentException("maxNodes must be 1.." + NODES_CAP);
            if (maxFlowsRead < 1 || maxFlowsRead > FLOWS_CAP) throw new IllegalArgumentException("maxFlowsRead must be 1.." + FLOWS_CAP);
        }
    }

    /**
     * @param tokenKey  token to follow ({@link #tokenKey(Token)}); null = every token of the seed, each one separately
     * @param minShare  a branch carrying less than this share of the seed's traced value is not followed (default 0.1 %)
     * @param dust      per-symbol thresholds (human units) below which a flow is not followed ({@code DetectionConfig})
     */
    public record Request(Network network, Address seed, Direction direction, Window window, String tokenKey, Method method,
                          Limits limits, Set<LabelCategory> stopAt, Fraction minShare, Map<String, BigDecimal> dust, Patterns patterns) {
        public static Request of(Address seed, Direction direction) {
            return new Request(seed.network(), seed, direction, Window.ALL, null, Method.PROPORTIONAL, Limits.DEFAULTS,
                    Set.of(LabelCategory.EXCHANGE, LabelCategory.BRIDGE), new Fraction(BigInteger.ONE, BigInteger.valueOf(1_000)), Map.of(),
                    Patterns.DEFAULTS);
        }
        public Request(Network network, Address seed, Direction direction, Window window, String tokenKey, Method method, Limits limits,
                       Set<LabelCategory> stopAt, Fraction minShare, Map<String, BigDecimal> dust) {
            this(network, seed, direction, window, tokenKey, method, limits, stopAt, minShare, dust, Patterns.DEFAULTS);
        }
        public Request withMethod(Method m) { return new Request(network, seed, direction, window, tokenKey, m, limits, stopAt, minShare, dust, patterns); }
        public Request withLimits(Limits l) { return new Request(network, seed, direction, window, tokenKey, method, l, stopAt, minShare, dust, patterns); }
        public Request withDust(Map<String, BigDecimal> d) { return new Request(network, seed, direction, window, tokenKey, method, limits, stopAt, minShare, d, patterns); }
        public Request withMinShare(Fraction f) { return new Request(network, seed, direction, window, tokenKey, method, limits, stopAt, f, dust, patterns); }
        public Request withPatterns(Patterns p) { return new Request(network, seed, direction, window, tokenKey, method, limits, stopAt, minShare, dust, p); }
        public Request withToken(String key) { return new Request(network, seed, direction, window, key, method, limits, stopAt, minShare, dust, patterns); }
    }

    /**
     * Thresholds of the patterns found on the bounded graph (all INFERRED findings).
     *
     * @param minFanOut     distinct recipients of traced value for a dispersion (as the FanOut detector: 20)
     * @param minFanIn      distinct senders for a consolidation
     * @param minPeels      consecutive detachments for a peel chain
     * @param maxPeelShare  largest share of the value passing through that a detachment may take
     * @param maxPeelSpread largest ratio between the biggest and the smallest detachment (regularity)
     */
    public record Patterns(int minFanOut, int minFanIn, int minPeels, Fraction maxPeelShare, Fraction maxPeelSpread) {
        public static final Patterns DEFAULTS = new Patterns(20, 20, 3, new Fraction(BigInteger.ONE, BigInteger.valueOf(5)),
                new Fraction(BigInteger.valueOf(3), BigInteger.TWO));
    }

    public enum FindingType { DISPERSION, CONSOLIDATION, CIRCULATION, PEEL_CHAIN }

    /**
     * A pattern on the traced graph. Always an inference (ADR-0023 amendment 1: {@code INFERRED}), never an identity or an
     * intent; cites the flows it rests on.
     */
    public record Finding(FindingType type, String level, List<Address> addresses, List<String> eventIds, String detail) {}

    /** What the engine reads. Implemented by the application (memory, journal, history); a list in tests. */
    public interface Source {
        /** Flows sent by the address (any order, duplicates allowed: the engine normalises). */
        List<ValueFlow> outgoing(Address a);
        /** Flows received by the address. */
        List<ValueFlow> incoming(Address a);
        /** True only when every flow of the address in the window is known (P0-B coverage COMPLETE). */
        boolean complete(Address a, Window w);
        /** Balance in raw units held just before the window (or before its first flow); empty = unknown. */
        Optional<BigInteger> openingBalance(Address a, String tokenKey, Window w);
        List<AddressLabel> labels(Address a);
    }

    /** Token identity used to keep assets apart: the contract, or {@code native:<symbol>}. */
    public static String tokenKey(Token t) { return t.isNative() ? "native:" + t.symbol() : t.contract(); }

    public enum StopReason {
        DEPTH, NODES, FLOWS_READ, STOP_CATEGORY, BELOW_MIN_SHARE, DUST, UNVERIFIED_TOKEN, COVERAGE_GAP, CYCLE,
        /** The value could have come from a balance held before the window, of unknown size: lower bound 0. */
        UNKNOWN_OPENING_BALANCE,
        /** Flows of the same instant in and out of the address: their order is unknown (no log index yet, D7). */
        SAME_TIME_ORDER;

        /** Stops that do not make the result partial (decisions, not missing data). */
        boolean benign() { return this == STOP_CATEGORY || this == BELOW_MIN_SHARE || this == DUST || this == UNVERIFIED_TOKEN; }
    }

    public record Stop(Address address, String tokenKey, StopReason reason, String detail) {}

    /**
     * Value attributed to the seed at one address (FORWARD: received from the seed; BACKWARD: came from this address),
     * in raw units of {@code tokenKey}. {@code low == high} when exact; otherwise an interval (unknown opening balance).
     *
     * @param partial some flows of an address on the way are unknown (coverage gap): neither bound is guaranteed
     */
    public record Attribution(Address address, String tokenKey, int depth, Fraction low, Fraction high, boolean partial) {}

    /** Value moved along one flow that the trace followed, with every identifier of that transfer. */
    public record Edge(String eventId, List<String> aliases, String txHash, Instant timestamp, Address from, Address to,
                       String tokenKey, BigInteger rawAmount, Fraction tracedLow, Fraction tracedHigh) {}

    public record Node(Address address, int depth, List<String> labels) {}

    public enum Status { COMPLETE, PARTIAL }

    public record Counters(int nodes, int flowsRead, int rounds) {}

    public record Result(Status status, Direction direction, Method method, List<Node> nodes, List<Edge> edges,
                         List<Attribution> attributions, List<Stop> stops, List<Finding> findings, Counters counters, String engineVersion) {}
}
