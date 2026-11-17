package io.openchain.intelligence.investigation;

import io.openchain.intelligence.investigation.InvestigationPlan.Assertion;
import io.openchain.intelligence.investigation.InvestigationPlan.Type;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The investigation report (ADR-0022 amendment 2), built only from stored assertions: every sentence is a fixed template
 * filled with one assertion and cites its id; there is no other text. Coverage and limits come first. Deterministic: the
 * same assertions give the same report.
 */
public final class InvestigationReport {
    private InvestigationReport() {}

    public static final String TEMPLATES = "report@1";

    public record Sentence(String text, String assertionId, String level) {}

    public record Section(String title, List<Sentence> sentences) {}

    public record Report(String plan, String templates, String subject, int assertions, List<Section> sections) {}

    private static final List<Map.Entry<String, Set<Type>>> SECTIONS = List.of(
            Map.entry("Coverage and limits", Set.of(Type.COVERAGE, Type.STEP_LIMIT, Type.TRACE_STOP)),
            Map.entry("Labels", Set.of(Type.LABEL)),
            Map.entry("Exposure to listed addresses", Set.of(Type.DIRECT_EXPOSURE, Type.IGNORED_TRANSFER)),
            Map.entry("Value flows", Set.of(Type.TRACE_ATTRIBUTION, Type.PATTERN)),
            Map.entry("Possibly related wallets", Set.of(Type.RELATION)),
            Map.entry("Signals from the detectors", Set.of(Type.SIGNAL)));

    public static Report build(String subject, List<Assertion> assertions) {
        Assertion coverage = assertions.stream().filter(a -> a.type() == Type.COVERAGE).findFirst().orElse(null);
        List<Section> out = new ArrayList<>();
        for (var sec : SECTIONS) {
            List<Sentence> sentences = new ArrayList<>();
            for (Assertion a : assertions) if (sec.getValue().contains(a.type())) sentences.add(new Sentence(text(a), a.id(), a.level().name()));
            // an empty exposure section is itself a statement about coverage, worded by its state and citing it
            if (sec.getValue().contains(Type.DIRECT_EXPOSURE) && coverage != null
                    && assertions.stream().noneMatch(a -> a.type() == Type.DIRECT_EXPOSURE)) {
                Map<String, String> p = coverage.params();
                String none = "COMPLETE".equals(p.get("state"))
                        ? "No direct exposure to a listed address in blocks " + p.get("fromBlock") + " to " + p.get("toBlock") + "."
                        : "No direct exposure to a listed address was found in the data covered; this is not proof that none exists.";
                sentences.add(new Sentence(none, coverage.id(), coverage.level().name()));
            }
            out.add(new Section(sec.getKey(), List.copyOf(sentences)));
        }
        return new Report(InvestigationPlan.VERSION, TEMPLATES, subject, assertions.size(), List.copyOf(out));
    }

    static String text(Assertion a) {
        Map<String, String> p = a.params();
        return switch (a.type()) {
            case COVERAGE -> "Data covered: " + p.get("state").toLowerCase()
                    + (p.containsKey("fromBlock") ? " (blocks " + p.get("fromBlock") + " to " + p.get("toBlock") + ")" : "") + "."
                    + (p.get("reasons") == null || p.get("reasons").isBlank() ? "" : " Limits: " + p.get("reasons") + ".")
                    + ("COMPLETE".equals(p.get("state")) ? "" : " A finding absent here may exist outside the data covered.");
            case LABEL -> "Source " + p.get("source") + " lists this address as " + p.get("category").toLowerCase().replace('_', ' ') + ".";
            case DIRECT_EXPOSURE -> "Direct transfers with " + p.get("counterparty") + ", listed as " + p.get("category").toLowerCase().replace('_', ' ')
                    + " by " + p.get("labelSource") + ": " + amounts(p) + ". Exposure describes where value came from or went; it does not qualify this address.";
            case IGNORED_TRANSFER -> "Ignored " + units(p.get("raw"), p.get("decimals")) + " " + p.get("symbol") + " from " + p.get("counterparty")
                    + " (" + p.get("category").toLowerCase().replace('_', ' ') + "): " + ("unverified_token".equals(p.get("reason"))
                    ? "an unverified token contract" : "below the dust threshold") + "; anyone can send such a transfer.";
            case TRACE_ATTRIBUTION -> ("FORWARD".equals(p.get("direction")) ? "Value sent by this address reached " : "Value received by this address came from ")
                    + p.get("address") + " (" + p.get("depth") + " hop(s)): " + range(p) + " " + p.getOrDefault("symbol", p.get("tokenKey"))
                    + ", attributed by the " + p.get("method").toLowerCase() + " convention" + ("true".equals(p.get("partial")) ? ", on partial data" : "") + ".";
            case TRACE_STOP -> "The " + ("FORWARD".equals(p.get("direction")) ? "outgoing" : "incoming") + " trace stopped at " + p.get("address") + ": "
                    + p.get("reason").toLowerCase().replace('_', ' ') + ".";
            case PATTERN -> "Pattern " + p.get("pattern").toLowerCase().replace('_', ' ') + " on the " + ("FORWARD".equals(p.get("direction")) ? "outgoing" : "incoming")
                    + " trace, among " + p.get("addresses") + ". An inference, not an intent.";
            case RELATION -> p.get("other") + " may be related to this address (" + p.get("relation").toLowerCase().replace('_', ' ')
                    + (p.get("via") == null || p.get("via").isBlank() ? "" : " via " + p.get("via")) + ", confidence " + p.get("confidence")
                    + "). Not proof of common ownership or control.";
            case SIGNAL -> "Detector " + p.get("detector") + " raised " + p.get("signalType").toLowerCase().replace('_', ' ') + " (" + p.get("severity").toLowerCase()
                    + ", confidence " + p.get("confidence") + "). A risk indicator, not a finding of wrongdoing.";
            case STEP_LIMIT -> "Step " + p.get("step") + " did not run: " + p.get("reason") + ".";
        };
    }

    private static String amounts(Map<String, String> p) {
        List<String> parts = new ArrayList<>();
        for (var e : p.entrySet()) {
            if (!e.getKey().startsWith("received:")) continue;
            String k = e.getKey().substring("received:".length());
            String dec = p.get("decimals:" + k), sym = p.getOrDefault("symbol:" + k, k);
            parts.add("received " + units(e.getValue(), dec) + " " + sym + ", sent " + units(p.get("sent:" + k), dec) + " " + sym);
        }
        return String.join("; ", parts);
    }

    private static String range(Map<String, String> p) {
        String low = fraction(p.get("low"), p.get("decimals")), high = fraction(p.get("high"), p.get("decimals"));
        return low.equals(high) ? low : low + " to " + high;
    }

    /** Decimals of a token as stored in an assertion; 0 when absent or malformed (the amount is then shown in raw units). */
    static int decimals(String decimals) {
        if (decimals == null) return 0;
        try {
            return Math.max(0, Integer.parseInt(decimals.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String units(String raw, String decimals) {
        int d = decimals(decimals);
        BigDecimal v = new BigDecimal(raw).movePointLeft(d).stripTrailingZeros();
        return v.signum() == 0 ? "0" : v.toPlainString();
    }

    /** An exact fraction "n/d" of raw units, shown in token units with at most 6 decimals (display only). */
    static String fraction(String f, String decimals) {
        int d = decimals(decimals);
        String[] nd = f.split("/");
        BigDecimal v = new BigDecimal(nd[0]);
        if (nd.length == 2) v = v.divide(new BigDecimal(nd[1]), MathContext.DECIMAL128);
        v = v.movePointLeft(d).setScale(6, RoundingMode.HALF_EVEN).stripTrailingZeros();
        return v.signum() == 0 ? "0" : v.toPlainString();
    }
}
