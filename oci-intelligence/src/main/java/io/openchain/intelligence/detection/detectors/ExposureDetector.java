package io.openchain.intelligence.detection.detectors;

import static io.openchain.core.detection.SignalText.*;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.core.model.Address;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.exposure.ExposurePaths;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.AddressLabel;
import io.openchain.intelligence.label.LabelCategory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

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

    /** Sees unverified-token flows to record them as zero-weight DUSTING; they never create exposure (see counts()). */
    @Override public boolean readsUnverifiedTokenFlows() { return true; }

    @Override public List<Signal> detect(DetectionContext ctx) {
        if (!ctx.labels().hasRisky()) return List.of(); // nothing listed: no exposure possible, skip the graph walk
        List<Signal> out = new ArrayList<>(direct(ctx));
        out.addAll(indirect(ctx));
        return out;
    }

    /** Paths are qualified by {@link ExposurePaths}, shared with the exposure endpoint; this class only words them. */
    private List<Signal> direct(DetectionContext ctx) {
        Address s = ctx.subject();
        List<Signal> out = new ArrayList<>();
        for (ExposurePaths.Direct d : ExposurePaths.direct(s, ctx.flows(), ctx.labels(), ctx.config().exposure())) {
            AddressLabel l = d.label();
            List<ValueFlow> fl = d.flows();
            if (!d.exposed()) {
                out.add(dusting(ctx, l, d.ignored()));
                continue;
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
        }
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

    private List<Signal> indirect(DetectionContext ctx) {
        List<Signal> out = new ArrayList<>();
        for (ExposurePaths.Indirect i : ExposurePaths.indirect(ctx.subject(), ctx.flows(), ctx.labels(), ctx.config().exposure())) {
            AddressLabel l = i.label();
            out.add(indirectSignal(ctx, l, i.path(), i.confidence(), "indirect|" + l.address() + "|" + l.category() + "|" + l.source()));
        }
        return out;
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
