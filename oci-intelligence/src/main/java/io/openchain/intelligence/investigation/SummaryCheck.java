package io.openchain.intelligence.investigation;

import io.openchain.intelligence.investigation.InvestigationPlan.Assertion;
import io.openchain.intelligence.investigation.InvestigationPlan.Level;
import io.openchain.intelligence.investigation.InvestigationPlan.Type;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic check of an AI summary (ADR-0022 amendment 5), the test "no claim outside the assertions". A summary
 * is a list of sentences, each citing the assertions it relies on. It is refused when a sentence cites nothing or an
 * unknown assertion; when an address or a number in it is not in the parameters of the assertions it cites; when it says
 * "confirmed" without a CONFIRMED assertion; when it states identity, ownership, control, intent or guilt, or uses a risk
 * category that none of its assertions carries. When coverage is not COMPLETE, the report's coverage sentence comes first.
 */
public final class SummaryCheck {
    private SummaryCheck() {}

    public static final int MAX_SENTENCES = 30;
    public static final int MAX_SENTENCE = 600;

    public record Sentence(String text, List<String> assertionIds) {
        public Sentence { assertionIds = assertionIds == null ? List.of() : List.copyOf(assertionIds); }
    }

    /** {@code sentences} is what may be shown when accepted (coverage first when it is partial). */
    public record Verdict(boolean accepted, List<String> reasons, List<Sentence> sentences) {}

    private static final Pattern EVM = Pattern.compile("0x[0-9a-fA-F]{6,}");
    private static final Pattern TRON = Pattern.compile("T[1-9A-HJ-NP-Za-km-z]{24,40}");
    private static final Pattern SHORT = Pattern.compile("(0x[0-9a-fA-F]{3,}|T[1-9A-HJ-NP-Za-km-z]{3,})(?:…|\\.\\.\\.)([0-9a-zA-Z]{3,})");
    private static final Pattern ID = Pattern.compile("\\bas_[0-9A-Za-z_]+");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?(?![\\w])|(?<![\\w.])\\d+(?:\\.\\d+)?(?![\\w])");
    private static final Pattern CONFIRM = Pattern.compile("\\bconfirm\\w*", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(own|owns|owned|owner\\w*|ownership|control|controls|controlled|controlling"
            + "|belong|belongs|belonging|same (?:person|entity|owner|individual|group)|launder\\w*|criminal\\w*|guilt\\w*|intent\\w*|intend\\w*"
            + "|illicit|illegal|fraud\\w*|terroris\\w*|perpetrat\\w*|suspect)\\b", Pattern.CASE_INSENSITIVE);
    /** Risk words allowed only when a cited assertion carries the category. */
    private static final Map<Pattern, String> CATEGORY_WORDS = Map.of(
            Pattern.compile("\\bsanction\\w*", Pattern.CASE_INSENSITIVE), "SANCTIONED",
            Pattern.compile("\\bscam\\w*", Pattern.CASE_INSENSITIVE), "SCAM",
            Pattern.compile("\\bphishing\\b", Pattern.CASE_INSENSITIVE), "PHISHING",
            Pattern.compile("\\bransomware\\b", Pattern.CASE_INSENSITIVE), "RANSOMWARE",
            Pattern.compile("\\bstolen\\b", Pattern.CASE_INSENSITIVE), "STOLEN_FUNDS",
            Pattern.compile("\\bmixer\\w*", Pattern.CASE_INSENSITIVE), "MIXER");

    public static Verdict check(List<Sentence> sentences, List<Assertion> assertions) {
        Map<String, Assertion> byId = new LinkedHashMap<>();
        for (Assertion a : assertions) byId.put(a.id(), a);
        List<String> reasons = new ArrayList<>();
        if (sentences == null || sentences.isEmpty()) reasons.add("the summary is empty");
        else if (sentences.size() > MAX_SENTENCES) reasons.add("more than " + MAX_SENTENCES + " sentences");
        List<Sentence> in = sentences == null ? List.of() : sentences;
        for (int i = 0; i < in.size(); i++) {
            Sentence s = in.get(i);
            String where = "sentence " + (i + 1) + ": ";
            String text = s.text() == null ? "" : s.text();
            if (text.isBlank()) reasons.add(where + "empty");
            if (text.length() > MAX_SENTENCE) reasons.add(where + "longer than " + MAX_SENTENCE + " characters");
            if (s.assertionIds().isEmpty()) { reasons.add(where + "cites no assertion"); continue; }
            List<Assertion> cited = new ArrayList<>();
            for (String id : s.assertionIds()) {
                Assertion a = byId.get(id);
                if (a == null) reasons.add(where + "unknown assertion " + id); else cited.add(a);
            }
            if (cited.size() != s.assertionIds().size()) continue;
            reasons.addAll(claims(where, text, cited));
        }
        List<Sentence> shown = new ArrayList<>(in);
        Assertion coverage = assertions.stream().filter(a -> a.type() == Type.COVERAGE).findFirst().orElse(null);
        if (coverage != null && !"COMPLETE".equals(coverage.params().get("state"))) {
            Sentence first = new Sentence(InvestigationReport.text(coverage), List.of(coverage.id()));
            if (shown.isEmpty() || !shown.getFirst().equals(first)) shown.addFirst(first);
        }
        return new Verdict(reasons.isEmpty(), List.copyOf(reasons), reasons.isEmpty() ? List.copyOf(shown) : List.of());
    }

    private static List<String> claims(String where, String text, List<Assertion> cited) {
        List<String> reasons = new ArrayList<>();
        Set<String> values = new HashSet<>();
        Set<String> categories = new HashSet<>();
        boolean confirmed = false;
        for (Assertion a : cited) {
            confirmed |= a.level() == Level.CONFIRMED;
            a.params().forEach((k, v) -> {
                if (v == null) return;
                values.add(v.toLowerCase(Locale.ROOT));
                for (String part : v.split(",")) values.add(part.trim().toLowerCase(Locale.ROOT));
                if (k.equals("category")) categories.add(v);
            });
        }
        // addresses, full and shortened, must be among the cited parameters
        String rest = text;
        Matcher sm = SHORT.matcher(text);
        while (sm.find()) {
            String head = sm.group(1).toLowerCase(Locale.ROOT), tail = sm.group(2).toLowerCase(Locale.ROOT);
            if (values.stream().noneMatch(v -> v.startsWith(head) && v.endsWith(tail))) reasons.add(where + "address " + sm.group() + " is not in its assertions");
        }
        rest = SHORT.matcher(rest).replaceAll(" ");
        for (Pattern p : List.of(EVM, TRON)) {
            Matcher m = p.matcher(rest);
            while (m.find()) if (!values.contains(m.group().toLowerCase(Locale.ROOT))) reasons.add(where + "address " + m.group() + " is not in its assertions");
            rest = p.matcher(rest).replaceAll(" ");
        }
        rest = ID.matcher(rest).replaceAll(" ");
        // numbers must be values of the cited assertions (exact, or the same value rounded to the precision written)
        List<BigDecimal> allowed = numbers(cited);
        Matcher nm = NUMBER.matcher(rest);
        while (nm.find()) {
            BigDecimal x = new BigDecimal(nm.group().replace(",", ""));
            boolean ok = allowed.stream().anyMatch(v -> v.setScale(Math.max(0, x.scale()), RoundingMode.HALF_EVEN).compareTo(x) == 0);
            if (!ok) reasons.add(where + "the number " + nm.group() + " is not in its assertions");
        }
        if (CONFIRM.matcher(text).find() && !confirmed) reasons.add(where + "says confirmed but none of its assertions is CONFIRMED");
        Matcher f = FORBIDDEN.matcher(text);
        if (f.find()) reasons.add(where + "states identity, ownership, control, intent or guilt (\"" + f.group() + "\")");
        CATEGORY_WORDS.forEach((p, cat) -> {
            Matcher c = p.matcher(text);
            if (c.find() && !categories.contains(cat)) reasons.add(where + "uses \"" + c.group() + "\" but none of its assertions carries " + cat);
        });
        return reasons;
    }

    /** Every number an assertion supports: its plain values, amounts in token units, fractions, percentages of confidences. */
    static List<BigDecimal> numbers(List<Assertion> cited) {
        List<BigDecimal> out = new ArrayList<>();
        for (Assertion a : cited) {
            Map<String, String> p = a.params();
            for (Map.Entry<String, String> e : p.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                if (v == null) continue;
                for (String part : v.split(",")) {
                    BigDecimal n = number(part.trim());
                    if (n == null) continue;
                    out.add(n);
                    if (k.equals("confidence")) out.add(n.movePointRight(2));
                    String dec = k.startsWith("received:") || k.startsWith("sent:") ? p.get("decimals:" + k.substring(k.indexOf(':') + 1))
                            : k.equals("low") || k.equals("high") || k.equals("raw") ? p.get("decimals") : null;
                    if (dec != null) out.add(n.movePointLeft(InvestigationReport.decimals(dec)));
                }
            }
        }
        return out;
    }

    private static BigDecimal number(String s) {
        try {
            String[] nd = s.split("/");
            if (nd.length == 2) return new BigDecimal(nd[0]).divide(new BigDecimal(nd[1]), MathContext.DECIMAL128);
            return new BigDecimal(s);
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
