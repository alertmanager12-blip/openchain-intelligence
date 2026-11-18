package io.openchain.intelligence.simulation;

import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.exposure.ExposurePaths;
import io.openchain.intelligence.flow.FlowStore;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.label.LabelRepository;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * "Who would be exposed if this address were listed?" (ADR-0023 amendment 2, level SIMULATED). The hypothesis is a label
 * visible to this computation only, through a read-only overlay of the label repository: nothing is stored, and the
 * exposure rules are exactly those of {@link ExposurePaths} (the same qualification as the detector and the exposure
 * endpoint). Candidates are the counterparties of the address and the wallets downstream of it up to the hop limit.
 */
public final class WhatIf {
    private WhatIf() {}

    public static final String LEVEL = "SIMULATED";
    /** Source name of the hypothetical label; never a real source. */
    public static final String HYPOTHESIS_SOURCE = "what-if-hypothesis";

    public record Hypothesis(Address address, LabelCategory category) {}

    /**
     * One wallet that would be exposed under the hypothesis.
     *
     * @param hops              1 for a direct counterparty, else the length of the value path from the address
     * @param txHashes          the transfers the exposure would rest on (direct: up to 5 newest; indirect: the path)
     * @param alreadyExposedTo  risky categories this wallet is already exposed to through real labels of the same address
     */
    public record Exposed(Address address, int hops, String level, List<String> txHashes, double confidence, List<String> alreadyExposedTo) {}

    /** {@code truncated}: the candidate limit or a budget was reached, so the list may be incomplete. */
    public record Result(String level, Hypothesis hypothesis, List<Exposed> exposed, int candidates, boolean truncated) {}

    public static Result exposedIfListed(Address x, LabelCategory category, FlowStore flows, LabelRepository labels, DetectionConfig.Exposure cfg,
                                         int maxCandidates) {
        if (!category.risky()) throw new IllegalArgumentException("category " + category + " creates no exposure");
        AddressLabel hypo = new AddressLabel(x, category, "hypothesis", HYPOTHESIS_SOURCE, 1.0, Instant.EPOCH, EvidenceLevel.RISK_SIGNAL,
                "what-if:" + x.value());
        LabelRepository overlay = new Overlay(labels, hypo);

        // candidates: direct counterparties (both directions), then wallets downstream of x, breadth first
        Set<Address> candidates = new LinkedHashSet<>();
        boolean truncated = false;
        for (ValueFlow f : flows.involving(x)) {
            Address o = f.counterparty(x);
            if (!o.equals(x)) candidates.add(o);
        }
        Deque<Address> frontier = new ArrayDeque<>(List.of(x));
        Set<Address> seen = new LinkedHashSet<>(List.of(x));
        for (int hop = 1; hop <= cfg.maxHops() && !frontier.isEmpty(); hop++) {
            Deque<Address> next = new ArrayDeque<>();
            for (Address n : frontier) {
                for (ValueFlow f : flows.outgoing(n)) {
                    if (seen.add(f.to())) { next.add(f.to()); candidates.add(f.to()); }
                }
            }
            frontier = next;
        }
        List<Address> ordered = new ArrayList<>(candidates);
        if (ordered.size() > maxCandidates) { truncated = true; ordered = ordered.subList(0, maxCandidates); }

        List<Exposed> out = new ArrayList<>();
        for (Address c : ordered) {
            Exposed e = null;
            for (ExposurePaths.Direct d : ExposurePaths.direct(c, flows, overlay, cfg)) {
                if (d.exposed() && isHypothesis(d.label())) {
                    e = new Exposed(c, 1, LEVEL, d.flows().reversed().stream().map(ValueFlow::txHash).distinct().limit(5).toList(),
                            d.label().sourceReliability(), already(c, x, flows, labels, cfg));
                }
            }
            if (e == null) {
                ExposurePaths.Indirect best = null;
                for (ExposurePaths.Indirect i : ExposurePaths.indirect(c, flows, overlay, cfg)) {
                    if (isHypothesis(i.label()) && (best == null || i.hops() < best.hops())) best = i;
                }
                if (best != null) e = new Exposed(c, best.hops(), LEVEL, best.path().stream().map(ValueFlow::txHash).toList(), best.confidence(),
                        already(c, x, flows, labels, cfg));
            }
            if (e != null) out.add(e);
        }
        out.sort(Comparator.comparingInt(Exposed::hops).thenComparing(e -> e.address().value()));
        return new Result(LEVEL, new Hypothesis(x, category), List.copyOf(out), ordered.size(), truncated);
    }

    private static boolean isHypothesis(AddressLabel l) { return HYPOTHESIS_SOURCE.equals(l.source()); }

    /** Real risky categories of x that already expose wallet c (same rules, real labels only). */
    private static List<String> already(Address c, Address x, FlowStore flows, LabelRepository labels, DetectionConfig.Exposure cfg) {
        Set<String> cats = new TreeSet<>();
        for (ExposurePaths.Direct d : ExposurePaths.direct(c, flows, labels, cfg)) {
            if (d.exposed() && d.label().address().equals(x)) cats.add(d.label().category().name());
        }
        for (ExposurePaths.Indirect i : ExposurePaths.indirect(c, flows, labels, cfg)) {
            if (i.label().address().equals(x)) cats.add(i.label().category().name());
        }
        return List.copyOf(cats);
    }

    /** The real labels plus the hypothesis, read-only: a simulation can never write a label. */
    private static final class Overlay implements LabelRepository {
        private final LabelRepository real;
        private final AddressLabel hypo;

        Overlay(LabelRepository real, AddressLabel hypo) {
            this.real = real;
            this.hypo = hypo;
        }

        @Override public void save(AddressLabel label) { throw new UnsupportedOperationException("a simulation never stores a label"); }

        @Override public List<AddressLabel> findByAddress(Address address) {
            List<AddressLabel> l = real.findByAddress(address);
            if (!address.equals(hypo.address())) return l;
            List<AddressLabel> out = new ArrayList<>(l);
            out.add(hypo);
            return out;
        }

        @Override public boolean remove(Address address, LabelCategory category, String source) {
            throw new UnsupportedOperationException("a simulation never removes a label");
        }

        @Override public int size() { return real.size() + 1; }
    }
}
