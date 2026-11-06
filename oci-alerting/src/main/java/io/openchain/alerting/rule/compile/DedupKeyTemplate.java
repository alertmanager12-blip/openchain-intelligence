package io.openchain.alerting.rule.compile;

import io.openchain.alerting.rule.FieldCatalog;
import io.openchain.core.event.ChainEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedup key template such as {@code "{{rule.id}}:{{event.transaction.from}}"}. This is placeholder substitution
 * only (no expressions). Allowed placeholders: {@code rule.id}, {@code rule.version}, {@code group.key},
 * {@code network} and {@code event.<catalog field>} (value of that field on the triggering event, empty if absent).
 */
public final class DedupKeyTemplate {

    public static final int MAX_LENGTH = 512;

    private sealed interface Part permits Literal, Placeholder {}
    private record Literal(String text) implements Part {}
    private record Placeholder(String name) implements Part {}

    private final List<Part> parts;
    private final String source;

    private DedupKeyTemplate(String source, List<Part> parts) { this.source = source; this.parts = List.copyOf(parts); }

    /** @throws IllegalArgumentException describing the first problem found */
    public static DedupKeyTemplate parse(String template, FieldCatalog catalog) {
        if (template == null || template.isEmpty()) throw new IllegalArgumentException("empty template");
        if (template.length() > MAX_LENGTH) throw new IllegalArgumentException("template longer than " + MAX_LENGTH);
        List<Part> parts = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            String literal = template.substring(i, open < 0 ? template.length() : open);
            if (literal.contains("}}")) throw new IllegalArgumentException("stray '}}' in template");
            if (!literal.isEmpty()) parts.add(new Literal(literal));
            if (open < 0) break;
            int close = template.indexOf("}}", open + 2);
            if (close < 0) throw new IllegalArgumentException("unterminated placeholder at index " + open);
            String name = template.substring(open + 2, close).trim();
            boolean ok = switch (name) {
                case "rule.id", "rule.version", "group.key", "network" -> true;
                default -> name.startsWith("event.") && catalog.contains(name.substring(6));
            };
            if (!ok) throw new IllegalArgumentException("unknown placeholder '{{" + name + "}}'");
            parts.add(new Placeholder(name));
            i = close + 2;
        }
        return new DedupKeyTemplate(template, parts);
    }

    public String render(String ruleId, String ruleVersion, String groupKey, ChainEvent event) {
        StringBuilder sb = new StringBuilder();
        for (Part p : parts) {
            switch (p) {
                case Literal l -> sb.append(l.text());
                case Placeholder ph -> sb.append(switch (ph.name()) {
                    case "rule.id" -> ruleId;
                    case "rule.version" -> ruleVersion;
                    case "group.key" -> groupKey;
                    case "network" -> event.network() == null ? "" : event.network().id();
                    default -> {
                        Object v = FieldAccess.get(event, ph.name().substring(6));
                        yield v == null ? "" : Values.display(v);
                    }
                });
            }
        }
        return sb.length() > MAX_LENGTH * 2 ? sb.substring(0, MAX_LENGTH * 2) : sb.toString();
    }

    public String source() { return source; }
}
