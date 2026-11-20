package io.openchain.alerting.rule;

import java.util.Map;
import java.util.TreeMap;

/** The catalogue of rule validation message keys with their English templates (published in docs/api/codes.json). */
public final class RuleMessages {

    private RuleMessages() {}

    public static Map<String, String> templates() {
        Map<String, String> out = new TreeMap<>();
        for (RuleMessage m : RuleMessage.values()) {
            if (out.put(m.key(), m.template()) != null) throw new IllegalStateException("duplicate rule key " + m.key());
        }
        return out;
    }
}
