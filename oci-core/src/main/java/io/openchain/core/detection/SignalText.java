package io.openchain.core.detection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Plain-language wording shared by detectors, scoring and the API: titles of signal types and label categories,
 * and number / duration / address formatting for analyst-facing sentences ("Sent 95% of its USDT balance
 * (35,000 USDT) to 2 addresses within 2 minutes"). Technical traces stay in the evidence and explanations.
 * The wording describes observed behaviour; it never qualifies the owner of an address.
 */
public final class SignalText {

    private SignalText() {}

    /** Short plain-language name of a signal type. */
    public static String title(SignalType t) {
        if (t == null) return "Signal";
        return switch (t) {
            case UNUSUAL_AMOUNT -> "Unusual amount";
            case REPETITIVE_TRANSFERS -> "Repetitive transfers";
            case STRUCTURING -> "Possible structuring";
            case FAST_FORWARDING -> "Fast forwarding";
            case BASELINE_DEVIATION -> "Deviation from usual activity";
            case DORMANT_REACTIVATION -> "Dormant wallet reactivated";
            case ADDRESS_PROLIFERATION -> "Address proliferation";
            case CIRCULAR_FLOW -> "Circular flow";
            case FLOW_CONCENTRATION -> "Fan-out to many recipients";
            case RAPID_DRAIN -> "Rapid drain";
            case SANCTIONED_EXPOSURE -> "Direct exposure to a sanctioned address";
            case FLAGGED_EXPOSURE -> "Direct exposure to a flagged address";
            case INDIRECT_EXPOSURE -> "Indirect exposure to a listed address";
            case MIXER_EXPOSURE -> "Direct exposure to a mixer";
            case SCAM_EXPOSURE -> "Direct exposure to a scam-reported address";
            case DUSTING -> "Dusting from a listed address";
            case OWNERSHIP_CHANGE -> "Contract ownership change";
            case DANGEROUS_ADMIN_PERMISSION -> "Dangerous admin permission";
            case MINT_CAPABILITY -> "Mint capability";
            case UNUSUAL_APPROVAL -> "Unlimited token approval";
            case MALICIOUS_CONTRACT_INTERACTION -> "Interaction with a listed contract";
        };
    }

    /** Plain-language name of a label category (enum name), e.g. {@code STOLEN_FUNDS -> "stolen funds"}. */
    public static String category(String categoryName) {
        if (categoryName == null) return "listed";
        return switch (categoryName) {
            case "SANCTIONED" -> "sanctioned";
            case "MIXER" -> "a mixer";
            case "SCAM" -> "a scam";
            case "PHISHING" -> "phishing";
            case "RANSOMWARE" -> "ransomware";
            case "STOLEN_FUNDS" -> "holding stolen funds";
            case "DARKNET_MARKET" -> "a darknet market";
            case "FLAGGED_COMMUNITY" -> "flagged by the community";
            case "ISSUER_FROZEN" -> "frozen by the stablecoin issuer";
            case "EXCHANGE" -> "an exchange";
            case "BRIDGE" -> "a bridge";
            case "DEFI_PROTOCOL" -> "a DeFi protocol";
            default -> categoryName.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    /** Title of a label factor, e.g. "Listed as sanctioned", "Frozen by the stablecoin issuer". */
    public static String labelTitle(String categoryName) {
        if ("ISSUER_FROZEN".equals(categoryName)) return "Frozen by the stablecoin issuer";
        return "Listed as " + category(categoryName);
    }

    /** Used when a producer gives no summary: the first "pattern"/"path"/"event" evidence, else the title. */
    static String fallbackSummary(SignalType type, List<Signal.Evidence> evidence) {
        for (String kind : List.of("pattern", "path", "event", "statistic")) {
            for (Signal.Evidence e : evidence) {
                if (kind.equals(e.kind()) && e.description() != null && !e.description().isBlank()) return sentence(e.description());
            }
        }
        return title(type) + " observed.";
    }

    // ---- formatting ---------------------------------------------------------------------------------------------

    /** Grouped number, at most 2 decimals (6 below 1): {@code 35000 -> "35,000"}, {@code 1250.5 -> "1,250.5"}, {@code 0.000001 -> "0.000001"}. */
    public static String number(BigDecimal v) {
        if (v == null) return "?";
        int decimals = v.abs().compareTo(BigDecimal.ONE) >= 0 ? 2 : 6;
        DecimalFormat f = new DecimalFormat("#,##0." + "#".repeat(decimals), DecimalFormatSymbols.getInstance(Locale.ROOT));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v);
    }

    /** Amount with its symbol: {@code "35,000 USDT"}. */
    public static String amount(BigDecimal v, String symbol) {
        return number(v) + (symbol == null || symbol.isBlank() ? "" : " " + symbol);
    }

    /** Percentage of a ratio, rounded: {@code 0.954 -> "95%"}; never shows 100% for a ratio below 1. */
    public static String percent(double ratio) {
        long p = Math.round(ratio * 100);
        if (p >= 100 && ratio < 1) p = 99;
        return p + "%";
    }

    /** Duration in words: "45 seconds", "2 minutes", "3 hours", "40 days". */
    public static String duration(Duration d) {
        long s = Math.max(0, d.toSeconds());
        if (s >= 2 * 86_400) return (s / 86_400) + " days";
        if (s >= 2 * 3_600) return (s / 3_600) + " hours";
        if (s >= 3_600) return "1 hour";
        if (s >= 120) return (s / 60) + " minutes";
        if (s >= 60) return "1 minute";
        return s == 1 ? "1 second" : s + " seconds";
    }

    /** Shortened address for sentences: {@code 0x1234…abcd} (short values unchanged). */
    public static String shortAddress(String a) {
        if (a == null) return "?";
        return a.length() <= 14 ? a : a.substring(0, 6) + "…" + a.substring(a.length() - 4);
    }

    /** "1 transfer" / "3 transfers". */
    public static String plural(long n, String singular, String plural) { return n + " " + (n == 1 ? singular : plural); }

    /** Capitalised first letter and a final period. */
    public static String sentence(String s) {
        if (s == null || s.isBlank()) return s;
        String t = s.strip();
        t = Character.toUpperCase(t.charAt(0)) + t.substring(1);
        return t.endsWith(".") ? t : t + ".";
    }
}
