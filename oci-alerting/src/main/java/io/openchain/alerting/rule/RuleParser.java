package io.openchain.alerting.rule;

import io.openchain.alerting.rule.ValidationError.Code;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.exceptions.Mark;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Safe loading of rule documents (YAML or JSON) into a generic JSON tree. Never binds to Java types, never
 * instantiates classes. YAML documents are pre-scanned at the event level and rejected if they contain explicit
 * tags ({@code !!java/...}, {@code !foo}), anchors/aliases (billion-laughs) or several documents.
 * Size, nesting depth and string length are capped before any tree is built.
 */
public final class RuleParser {

    public enum Format {
        YAML, JSON;

        public static Format fromFileName(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.endsWith(".json")) return JSON;
            if (n.endsWith(".yaml") || n.endsWith(".yml")) return YAML;
            throw new IllegalArgumentException("unsupported rule file extension: " + name);
        }
    }

    /** A document that cannot be turned into a tree. */
    public static final class RuleParseException extends RuntimeException {
        private final ValidationError error;
        RuleParseException(Code code, RuleMessage m, Object... kv) {
            this(ValidationError.of("$", code, m, kv));
        }
        private RuleParseException(ValidationError error) {
            super(error.message());
            this.error = error;
        }
        public ValidationError error() { return error; }
    }

    private static final int MAX_NESTING = 32;

    private final int maxSourceBytes;
    private final LoadSettings yamlSettings;
    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public RuleParser(RuleLimits limits) {
        this.maxSourceBytes = limits.maxSourceBytes();
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING).maxDocumentLength(maxSourceBytes)
                .maxStringLength(Math.max(64 * 1024, limits.maxStringLength())).maxNumberLength(100).build();
        this.yamlSettings = LoadSettings.builder().setAllowDuplicateKeys(false).setMaxAliasesForCollections(0)
                .setAllowRecursiveKeys(false).setCodePointLimit(maxSourceBytes).setUseMarks(true).build();
        this.jsonMapper = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(constraints).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        this.yamlMapper = YAMLMapper.builder(YAMLFactory.builder().loadSettings(yamlSettings)
                        .streamReadConstraints(constraints).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    }

    public RuleParser() { this(RuleLimits.defaults()); }

    public JsonNode parse(String source, Format format) {
        if (source == null || source.isBlank()) throw new RuleParseException(Code.SYNTAX, RuleMessage.EMPTY_DOCUMENT);
        if (source.getBytes(StandardCharsets.UTF_8).length > maxSourceBytes)
            throw new RuleParseException(Code.LIMIT_EXCEEDED, RuleMessage.DOCUMENT_TOO_LARGE, "max", maxSourceBytes);
        if (format == Format.YAML) prescanYaml(source);
        try {
            JsonNode root = (format == Format.YAML ? yamlMapper : jsonMapper).readTree(source);
            if (root == null || root.isMissingNode() || !root.isObject())
                throw new RuleParseException(Code.TYPE, RuleMessage.DOCUMENT_NOT_OBJECT);
            return root;
        } catch (JacksonException e) {
            throw new RuleParseException(Code.SYNTAX, RuleMessage.MALFORMED_DOCUMENT, "format", format, "reason", e.getOriginalMessage());
        }
    }

    private void prescanYaml(String source) {
        int documents = 0;
        int depth = 0;
        try {
            for (Event ev : new Parse(yamlSettings).parseString(source)) {
                switch (ev.getEventId()) {
                    case Alias -> throw unsafe(RuleMessage.YAML_ALIASES, ev);
                    case DocumentStart -> { if (++documents > 1) throw unsafe(RuleMessage.YAML_MULTIPLE_DOCUMENTS, ev); }
                    case MappingStart, SequenceStart -> { if (++depth > MAX_NESTING) throw unsafe(RuleMessage.YAML_NESTED_TOO_DEEPLY, ev); }
                    case MappingEnd, SequenceEnd -> depth--;
                    default -> { }
                }
                if (ev instanceof NodeEvent ne && ne.getAnchor().isPresent())
                    throw unsafe(RuleMessage.YAML_ANCHORS, ev);
                List<String> tags = ev instanceof ScalarEvent s ? s.getTag().stream().toList()
                        : ev instanceof CollectionStartEvent c ? c.getTag().stream().toList() : List.of();
                if (!tags.isEmpty()) throw unsafe(RuleMessage.YAML_TAGS, ev, "tag", tags.getFirst());
            }
        } catch (RuleParseException e) {
            throw e;
        } catch (RuntimeException e) { // snakeyaml-engine YamlEngineException and friends
            throw new RuleParseException(Code.SYNTAX, RuleMessage.MALFORMED_YAML, "reason", firstLine(e.getMessage()));
        }
    }

    private static RuleParseException unsafe(RuleMessage m, Event ev, Object... extra) {
        Mark mark = ev.getStartMark().orElse(null);
        Object[] kv = java.util.Arrays.copyOf(extra, extra.length + 4);
        kv[extra.length] = "line";
        kv[extra.length + 1] = mark == null ? 0 : mark.getLine() + 1;
        kv[extra.length + 2] = "column";
        kv[extra.length + 3] = mark == null ? 0 : mark.getColumn() + 1;
        return new RuleParseException(Code.UNSAFE_DOCUMENT, m, kv);
    }

    private static String firstLine(String s) {
        if (s == null) return "unknown error";
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
