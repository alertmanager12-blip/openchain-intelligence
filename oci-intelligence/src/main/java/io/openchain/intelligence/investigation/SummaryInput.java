package io.openchain.intelligence.investigation;

import io.openchain.intelligence.investigation.InvestigationPlan.Assertion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * What an AI summary is given (ADR-0022 amendment 5): the assertions of one investigation and nothing else (no evidence,
 * no raw transfers), with every on-chain text filtered. Token symbols and label texts come from contracts and lists anyone
 * can write: a value outside a small safe alphabet is replaced, so that no instruction can reach the model through them.
 */
public final class SummaryInput {
    private SummaryInput() {}

    public static final String FILTERED = "[filtered]";

    private static final Pattern SYMBOL = Pattern.compile("^[A-Za-z0-9.$_-]{1,16}$");
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9 ._:/,@+=|#-]{0,4096}$");

    public static List<Map<String, Object>> payload(List<Assertion> assertions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Assertion a : assertions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id());
            m.put("type", a.type().name());
            m.put("level", a.level().name());
            Map<String, String> p = new TreeMap<>();
            a.params().forEach((k, v) -> p.put(k, value(k, v, a.params())));
            m.put("params", p);
            out.add(m);
        }
        return out;
    }

    static String value(String key, String v, Map<String, String> params) {
        if (v == null) return "";
        if (key.equals("symbol") || key.startsWith("symbol:")) {
            if (SYMBOL.matcher(v).matches()) return v;
            String tokenKey = key.startsWith("symbol:") ? key.substring("symbol:".length()) : params.getOrDefault("tokenKey", "");
            return "token " + shorten(tokenKey);
        }
        return SAFE.matcher(v).matches() ? v : FILTERED;
    }

    static String shorten(String s) {
        if (s == null || s.isBlank() || !SAFE.matcher(s).matches()) return "?";
        return s.length() > 12 ? s.substring(0, 6) + "…" + s.substring(s.length() - 4) : s;
    }
}
