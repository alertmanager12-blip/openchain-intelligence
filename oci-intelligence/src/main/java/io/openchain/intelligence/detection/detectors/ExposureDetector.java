package io.openchain.intelligence.detection.detectors;

import static io.openchain.core.detection.SignalText.*;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionConfig;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exposure to addresses that a label source lists in a risky category.
 * <ul>
 * <li>Direct (1 hop, both directions): SANCTIONED_EXPOSURE / MIXER_EXPOSURE / SCAM_EXPOSURE / FLAGGED_EXPOSURE.
 *     Evidence level is CONFIRMED_BY_SOURCE only when the label itself is (the flow is an on-chain fact).</li>
 * <li>Indirect (2..maxHops, incoming value only): INDIRECT_EXPOSURE, RISK_SIGNAL, confidence decayed per hop.
 *     Only through actual, time-consistent value flows (each upstream flow happened before the downstream one).
 *     Stops at the labelled source and at {@code stopAt} categories. Evidence lists the full path.</li>
 * </ul>
 * Exposure describes where value came from / went to; it never qualifies the subject.
 * <p>Anti-griefing (anyone can send dust, or emit a fake-token Transfer whose {@code from} is a sanctioned address):
 * flows of unverified tokens and inbound flows below the per-symbol dust threshold create no exposure, direct or
 * indirect. When they are the only link to a listed address, a DUSTING anomaly (zero score weight) records them.
 */
public final class ExposureDetector extends AbstractDetector {
    public ExposureDetector() { super("exposure", 1); }

    private record Node(Address address, List<ValueFlow> path) {} // path[0] = flow leaving the source ... path[last] = flow into subject

    @Override public List<Signal> detect(DetectionContext ctx) {
        if (!ctx.labels().hasRisky()) return List.of(); // nothing listed: no exposure possible, skip the graph walk
        List<Signal> out = new ArrayList<>(direct(ctx));
        out.addAll(indirect(ctx));
        return out;
    }

    /** True when this flow may carry exposure into {@code subject}'s view (see class doc). */
    private static boolean counts(ValueFlow f, Address subject, DetectionConfig.Exposure cfg) {
        if (f.token().isUnverified()) return false;
        boolean inbound = f.to().equals(subject);
        return !inbound || f.amount().compareTo(cfg.dustThreshold(f.token().symbol())) >= 0;
    }

    private List<Signal> direct(DetectionContext ctx) {
        Address s = ctx.subject();
        var cfg = ctx.config().exposure();
        Map<String, List<ValueFlow>> flowsByLabel = new LinkedHashMap<>();
        Map<String, List<ValueFlow>> dustByLabel = new LinkedHashMap<>();
        Map<String, AddressLabel> labelByKey = new LinkedHashMap<>();
        for (ValueFlow f : ctx.flows().involving(s)) {
            if (f.from().equals(f.to())) continue;
            for (AddressLabel l : ctx.labels().findRisky(f.counterparty(s))) {
                labelByKey.putIfAbsent(l.key(), l);
                (counts(f, s, cfg) ? flowsByLabel : dustByLabel).computeIfAbsent(l.key(), k -> new ArrayList<>()).add(f);
            }
        }
        List<Signal> out = new ArrayList<>();
        labelByKey.forEach((key, l) -> {
            List<ValueFlow> fl = flowsByLabel.get(key);
            if (fl == null) {
                out.add(dusting(ctx, l, dustByLabel.get(key)));
                return;
            }
            Map<String, BigDecimal[]> perToken = new java.util.TreeMap<>();
            for (ValueFlow f : fl) {
                BigDecimal[] io = perToken.computeIfAbsent(f.token().symbol(), k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                if (f.to().equals(s)) io[0] = io[0].add(f.amount()); else io[1] = io[1].add(f.amount());
            }
            StringBuilder amounts = new StringBuilder();
            perToken.forEach((sym, io) -> amounts.append(amounts.isEmpty() ? "" : "; ").append(sym)
                    .append(" received ").append(plain(io[0])).append(", sent ").append(plain(io[1])));
            List<Evidence> ev = new ArrayList<>();
            ev.add(labelEvidence(l));
            ev.addAll(flowEvidence(fl.reversed(), "direct flow with listed address", ctx.config().maxEvidenceItems()));
            ev.add(new Evidence("hops", "1", fl.getLast().txHash(), qualifiedId(), fl.getLast().timestamp()));
            ev.add(new Evidence("pattern", "direct exposure to address listed as " + l.category() + " by " + l.source()
                    + ": " + fl.size() + " transfer(s); " + amounts,
                    fl.getLast().txHash(), qualifiedId(), fl.getLast().timestamp()));
            EvidenceLevel lvl = l.evidenceLevel() == EvidenceLevel.CONFIRMED_BY_SOURCE ? EvidenceLevel.CONFIRMED_BY_SOURCE : EvidenceLevel.RISK_SIGNAL;
            out.add(signal(ctx, l.category().exposureType(), s, fl.getLast().txHash(), directSeverity(l.category()),
                    l.sourceReliability(), lvl, ev, "direct|" + l.address() + "|" + l.category() + "|" + l.source(),
                    directSummary(l, perToken, fl.size())));
        });
        return out;
    }

    private Signal dusting(DetectionContext ctx, AddressLabel l, List<ValueFlow> fl) {
        var cfg = ctx.config().exposure();
        long unverified = fl.stream().filter(f -> f.token().isUnverified()).count();
        List<Evidence> ev = new ArrayList<>();
        ev.add(labelEvidence(l));
        ev.addAll(flowEvidence(fl.reversed(), "ignored inbound transfer from listed address", ctx.config().maxEvidenceItems()));
        ev.add(new Evidence("pattern", fl.size() + " unsolicited inbound transfer(s) from address listed as " + l.category()
                + " by " + l.source() + " ignored for exposure: " + unverified + " of an unverified token contract, "
                + (fl.size() - unverified) + " below the dust threshold (" + cfg.dustThresholds() + ")",
                fl.getLast().txHash(), qualifiedId(), fl.getLast().timestamp()));
        String summary = "Received " + plural(fl.size(), "unsolicited transfer", "unsolicited transfers") + " from an address listed as "
                + category(l.category().name()) + " by " + l.source() + " (" + (unverified == fl.size() ? "unverified token"
                : unverified == 0 ? "below the dust threshold" : "unverified token or below the dust threshold")
                + "); not counted as exposure because anyone can send such transfers.";
        return signal(ctx, SignalType.DUSTING, ctx.subject(), fl.getLast().txHash(), Severity.INFO, l.sourceReliability(),
                EvidenceLevel.ANOMALY, ev, "dusting|" + l.address() + "|" + l.category() + "|" + l.source(), summary);
    }

    /** Indirect tracing follows inbound value only: same rule as {@link #counts} for inbound flows. */
    private static boolean traceable(ValueFlow f, DetectionConfig.Exposure cfg) {
        return !f.token().isUnverified() && f.amount().compareTo(cfg.dustThreshold(f.token().symbol())) >= 0;
    }

    private List<Signal> indirect(DetectionContext ctx) {
        var cfg = ctx.config().exposure();
        if (cfg.maxHops() < 2) return List.of();
        Address s = ctx.subject();
        Deque<Node> queue = new ArrayDeque<>();
        Set<Address> visited = new HashSet<>(Set.of(s));
        int budget = cfg.maxVisitedFlows();
        for (ValueFlow f : ctx.flows().incoming(s)) {
            if (f.from().equals(s) || !traceable(f, cfg) || !visited.add(f.from())) continue;
            queue.add(new Node(f.from(), List.of(f)));
        }
        Map<String, Signal> found = new LinkedHashMap<>();
        while (!queue.isEmpty() && budget > 0 && found.size() < cfg.maxPaths()) {
            Node n = queue.poll();
            List<AddressLabel> nodeLabels = ctx.labels().findByAddress(n.address());
            boolean risky = nodeLabels.stream().anyMatch(l -> l.category().risky());
            if (n.path().size() >= 2) {
                for (AddressLabel l : nodeLabels) {
                    if (!l.category().risky()) continue;
                    String disc = "indirect|" + l.address() + "|" + l.category() + "|" + l.source();
                    if (found.containsKey(disc)) continue;
                    double conf = l.sourceReliability() * Math.pow(cfg.hopDecay(), n.path().size() - 1);
                    if (conf < cfg.minConfidence()) continue;
                    found.put(disc, indirectSignal(ctx, l, n.path(), conf, disc));
                }
            }
            boolean stop = risky || nodeLabels.stream().anyMatch(l -> cfg.stopAt().contains(l.category()));
            if (stop || n.path().size() >= cfg.maxHops()) continue;
            ValueFlow downstream = n.path().getFirst();
            for (ValueFlow g : ctx.flows().incoming(n.address())) {
                if (--budget <= 0) break;
                if (g.timestamp().isAfter(downstream.timestamp()) || g.from().equals(n.address()) || !traceable(g, cfg)
                        || !visited.add(g.from())) continue;
                List<ValueFlow> p = new ArrayList<>(n.path().size() + 1);
                p.add(g); p.addAll(n.path());
                queue.add(new Node(g.from(), p));
            }
        }
        return new ArrayList<>(found.values());
    }

    private Signal indirectSignal(DetectionContext ctx, AddressLabel l, List<ValueFlow> path, double conf, String disc) {
        int hops = path.size();
        StringBuilder route = new StringBuilder(l.address().value());
        for (ValueFlow f : path) route.append(" -> ").append(f.to().value());
        ValueFlow bottleneck = path.stream().min(Comparator.comparing(ValueFlow::amount)).orElseThrow();
        List<Evidence> ev = new ArrayList<>();
        ev.add(labelEvidence(l));
        ev.addAll(flowEvidence(path, "path hop", ctx.config().maxEvidenceItems()));
        ev.add(new Evidence("hops", String.valueOf(hops), path.getLast().txHash(), qualifiedId(), path.getLast().timestamp()));
        ev.add(new Evidence("path", hops + "-hop value path from address listed as " + l.category() + " by " + l.source() + ": " + route
                + "; traced value bounded by smallest hop " + plain(bottleneck.amount()) + " " + bottleneck.token().symbol()
                + String.format(java.util.Locale.ROOT, "; confidence %.2f x %.2f^%d", l.sourceReliability(), ctx.config().exposure().hopDecay(), hops - 1),
                path.getLast().txHash(), qualifiedId(), path.getLast().timestamp()));
        Severity sev = switch (hops) {
            case 2 -> l.category() == LabelCategory.SANCTIONED ? Severity.HIGH : Severity.MEDIUM;
            default -> l.category() == LabelCategory.SANCTIONED ? Severity.MEDIUM : Severity.LOW;
        };
        String summary = "Received funds that trace back, through " + plural(hops - 1, "intermediary address", "intermediary addresses")
                + ", to an address listed as " + category(l.category().name()) + " by " + l.source() + " (at most "
                + amount(bottleneck.amount(), bottleneck.token().symbol()) + " traced).";
        return signal(ctx, SignalType.INDIRECT_EXPOSURE, ctx.subject(), path.getLast().txHash(), sev, conf, EvidenceLevel.RISK_SIGNAL, ev, disc, summary);
    }

    /** "Received 12,500 USDT directly from an address listed as sanctioned by ofac-sdn" (sent / both directions alike). */
    static String directSummary(AddressLabel l, Map<String, BigDecimal[]> perToken, int transfers) {
        List<String> in = new ArrayList<>(), out = new ArrayList<>();
        perToken.forEach((sym, io) -> {
            if (io[0].signum() > 0) in.add(amount(io[0], sym));
            if (io[1].signum() > 0) out.add(amount(io[1], sym));
        });
        String who = "an address listed as " + category(l.category().name()) + " by " + l.source();
        String verb = !in.isEmpty() && !out.isEmpty() ? "Received " + String.join(" and ", in) + " from and sent "
                + String.join(" and ", out) + " directly to " + who
                : !in.isEmpty() ? "Received " + String.join(" and ", in) + " directly from " + who
                : "Sent " + String.join(" and ", out) + " directly to " + who;
        return verb + (transfers > 1 ? " (" + transfers + " transfers)." : ".");
    }

    private static Evidence labelEvidence(AddressLabel l) {
        return new Evidence("label", "source '" + l.source() + "' lists " + l.address().value() + " as " + l.category()
                + (l.name() == null ? "" : " (" + l.name() + ")") + ", reliability " + l.sourceReliability(),
                l.reference(), l.source(), l.observedAt());
    }

    private static Severity directSeverity(LabelCategory c) {
        return switch (c) {
            case SANCTIONED -> Severity.CRITICAL;
            case MIXER, RANSOMWARE, STOLEN_FUNDS, DARKNET_MARKET, SCAM, PHISHING -> Severity.HIGH;
            default -> Severity.MEDIUM;
        };
    }
}
