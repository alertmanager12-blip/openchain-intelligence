package io.openchain.intelligence.detection.detectors;

import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal;
import io.openchain.core.detection.Signal.Evidence;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.detection.SignalType;
import io.openchain.intelligence.detection.DetectionContext;
import io.openchain.intelligence.flow.ValueFlow;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;

/**
 * Amount far above the wallet's own baseline for the same token (upper tail only).
 * Robust z = 0.6745 * (x - median) / MAD; falls back to 1.2533 * mean absolute deviation when MAD = 0.
 * Pure statistics: emitted as ANOMALY, never as a risk qualification.
 */
public final class UnusualAmountDetector extends AbstractDetector {
    private static final MathContext MC = MathContext.DECIMAL64;

    public UnusualAmountDetector() { super("unusual-amount", 1); }

    @Override public List<Signal> detect(DetectionContext ctx) {
        ValueFlow t = ctx.trigger();
        if (t == null || !t.involves(ctx.subject())) return List.of();
        var cfg = ctx.config().unusualAmount();
        List<BigDecimal> hist = new ArrayList<>();
        for (ValueFlow f : sameToken(ctx.flows().involving(ctx.subject()), t))
            if (!f.eventId().equals(t.eventId()) && !f.timestamp().isAfter(t.timestamp())) hist.add(f.amount());
        if (hist.size() < cfg.minHistory()) return List.of();
        BigDecimal x = t.amount();
        BigDecimal med = median(hist);
        if (x.compareTo(med) <= 0) return List.of();
        List<BigDecimal> dev = hist.stream().map(h -> h.subtract(med).abs()).toList();
        BigDecimal scale = median(dev).multiply(new BigDecimal("1.4826"), MC); // 1/0.6745
        String method = "MAD";
        if (scale.signum() == 0) {
            scale = dev.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(dev.size()), MC).multiply(new BigDecimal("1.2533"), MC);
            method = "meanAD";
        }
        double z = scale.signum() == 0 ? Double.POSITIVE_INFINITY : x.subtract(med).divide(scale, MC).doubleValue();
        if (z < cfg.zThreshold()) return List.of();
        Severity sev = z >= 2 * cfg.zThreshold() ? Severity.MEDIUM : Severity.LOW;
        double conf = Math.min(0.9, 0.3 + 0.02 * hist.size());
        String zs = Double.isInfinite(z) ? "inf (constant history)" : String.format(java.util.Locale.ROOT, "%.2f", z);
        var ev = List.of(
                flowEvidence(t, "unusual transfer"),
                new Evidence("statistic", "amount " + plain(x) + " " + t.token().symbol() + " vs own median " + plain(med)
                        + " (" + method + ", n=" + hist.size() + "), robust z=" + zs + " >= " + cfg.zThreshold(),
                        t.txHash(), qualifiedId(), t.timestamp()));
        return List.of(signal(ctx, SignalType.UNUSUAL_AMOUNT, ctx.subject(), t.txHash(), sev, conf, EvidenceLevel.ANOMALY, ev, t.eventId()));
    }

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> s = new ArrayList<>(values);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : s.get(n / 2 - 1).add(s.get(n / 2)).divide(BigDecimal.TWO, MC);
    }
}
