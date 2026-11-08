package io.openchain.app.rules;

import io.openchain.alerting.rule.RuleParser;
import io.openchain.alerting.rule.RuleParser.Format;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and replaces the {@code actions} of a rule source, so that an operator's notification routing survives a rule
 * pack update (see {@code RuleRegistry#loadDirectory}). The rest of the source is kept verbatim: in YAML only the
 * top-level {@code actions:} block is rewritten (as a one-line flow sequence, valid YAML), comments and layout elsewhere
 * are untouched.
 */
final class RuleActions {
    private static final RuleParser PARSER = new RuleParser();
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private RuleActions() {}

    /** The actions of a source (an empty array when it has none). */
    static JsonNode of(String source, Format format) {
        JsonNode a = PARSER.parse(source, format).get("actions");
        return a == null || a.isNull() ? JSON.createArrayNode() : a;
    }

    static String json(JsonNode actions) { return JSON.writeValueAsString(actions); }

    /** {@code source} with its actions replaced by {@code actionsJson} (a JSON array). */
    static String replace(String source, Format format, String actionsJson) {
        JsonNode actions = JSON.readTree(actionsJson);
        if (!actions.isArray()) throw new IllegalArgumentException("actions override must be a JSON array");
        String flow = JSON.writeValueAsString(actions);
        if (format == Format.JSON) {
            ObjectNode root = (ObjectNode) JSON.readTree(source);
            root.set("actions", actions);
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        }
        List<String> lines = new ArrayList<>(Arrays.asList(source.split("\n", -1)));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^actions\\s*:.*")) { start = i; break; }
        }
        if (start < 0) {
            String sep = source.endsWith("\n") ? "" : "\n";
            return source + sep + "actions: " + flow + "\n";
        }
        int end = start + 1; // the block: indented lines, column-0 list items and blank lines, up to the next top-level key
        while (end < lines.size()) {
            String l = lines.get(end);
            if (l.isBlank() || l.startsWith(" ") || l.startsWith("\t") || l.startsWith("-")) end++;
            else break;
        }
        while (end > start + 1 && lines.get(end - 1).isBlank()) end--; // keep the blank lines before the next key
        List<String> out = new ArrayList<>(lines.subList(0, start));
        out.add("actions: " + flow);
        out.addAll(lines.subList(end, lines.size()));
        return String.join("\n", out);
    }
}
