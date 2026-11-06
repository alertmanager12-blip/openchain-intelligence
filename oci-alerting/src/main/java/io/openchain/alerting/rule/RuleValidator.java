package io.openchain.alerting.rule;

import io.openchain.alerting.rule.Condition.Group;
import io.openchain.alerting.rule.Condition.Leaf;
import io.openchain.alerting.rule.Condition.Logic;
import io.openchain.alerting.rule.FieldCatalog.FieldDef;
import io.openchain.alerting.rule.Rule.ActionSpec;
import io.openchain.alerting.rule.Rule.Aggregation;
import io.openchain.alerting.rule.Rule.RuleExample;
import io.openchain.alerting.rule.Rule.RuleTestCase;
import io.openchain.alerting.rule.Rule.Scope;
import io.openchain.alerting.rule.Rule.Sequence;
import io.openchain.alerting.rule.Rule.SequenceStep;
import io.openchain.alerting.rule.Rule.TestEvent;
import io.openchain.alerting.rule.ValidationError.Code;
import io.openchain.alerting.rule.compile.DedupKeyTemplate;
import io.openchain.alerting.rule.compile.SafeRegex;
import io.openchain.alerting.rule.compile.Values;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.event.ChainEvent.EventType;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Strict validator for the {@code openchain.io/rule/v1} schema (see {@code schema/rule-v1.schema.json}).
 * Collects every error (not just the first) with its JSON path, enforces the field whitelist and {@link RuleLimits},
 * and produces a typed {@link Rule}. Unknown properties are errors: there is no "free-form" extension point.
 */
public final class RuleValidator {

    static final Pattern SLUG = Pattern.compile("^[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$");
    static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})(?:-[0-9A-Za-z.-]{1,32})?$");
    static final Pattern DURATION_SHORT = Pattern.compile("^([1-9]\\d{0,6})(s|m|h|d)$");
    static final Pattern PARAM_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,40}$");
    /** Base instant for test/example event offsets. */
    public static final Instant TEST_EPOCH = Instant.parse("2026-01-01T00:00:00Z");
    public static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(15);
    public static final String DEFAULT_BALANCE_FIELD = "wallet.balance";

    private static final Set<String> TOP = Set.of("apiVersion", "id", "version", "name", "description", "author",
            "enabled", "scope", "conditions", "aggregation", "sequence", "severity", "evidenceLevel", "actions",
            "cooldown", "dedupKey", "documentation", "expectedConfidence", "tags", "examples", "tests");

    private final FieldCatalog catalog;
    private final RuleLimits limits;

    public RuleValidator(FieldCatalog catalog, RuleLimits limits) {
        this.catalog = catalog;
        this.limits = limits;
    }

    public RuleValidator() { this(FieldCatalog.defaults(), RuleLimits.defaults()); }

    public ValidationResult validate(JsonNode root) {
        Run r = new Run();
        Rule rule = r.rule(root);
        return r.errors.isEmpty() ? new ValidationResult(List.of(), rule) : ValidationResult.failure(r.errors);
    }

    /** Per-validation mutable state. */
    private final class Run {
        final List<ValidationError> errors = new ArrayList<>();
        int conditionCount;
        boolean sourceBackedFieldUsed;
        Scope scope;

        void err(String path, Code code, String msg) {
            if (errors.size() < 200) errors.add(new ValidationError(path, code, msg));
        }

        Rule rule(JsonNode root) {
            if (root == null || !root.isObject()) { err("$", Code.TYPE, "rule must be an object"); return null; }
            unknown(root, "$", TOP);
            String api = str(root, "apiVersion", "$", true, 64);
            if (api != null && !Rule.API_VERSION.equals(api))
                err("$.apiVersion", Code.UNSUPPORTED_API_VERSION, "unsupported apiVersion '" + api + "', expected " + Rule.API_VERSION);
            String id = pattern(root, "id", "$", true, SLUG, "lowercase slug (a-z, 0-9, '-'), max 64 chars");
            String version = pattern(root, "version", "$", true, SEMVER, "semantic version MAJOR.MINOR.PATCH");
            String name = str(root, "name", "$", true, 200);
            String description = str(root, "description", "$", false, 4096);
            String author = str(root, "author", "$", true, 200);
            boolean enabled = bool(root, "enabled", "$", true);
            scope = scope(root.get("scope"), "$.scope");

            JsonNode seqNode = root.get("sequence");
            JsonNode condNode = root.get("conditions");
            Condition conditions;
            if (condNode == null) {
                if (seqNode == null) err("$.conditions", Code.REQUIRED, "conditions are required (except for sequence rules)");
                conditions = Group.matchAll();
            } else {
                conditions = group(condNode, "$.conditions", 1);
            }
            Aggregation aggregation = root.has("aggregation") ? aggregation(root.get("aggregation"), "$.aggregation") : null;
            Sequence sequence = seqNode != null ? sequence(seqNode, "$.sequence") : null;
            if (aggregation != null && sequence != null)
                err("$", Code.CONFLICT, "a rule cannot declare both aggregation and sequence");
            if (conditionCount > limits.maxConditions())
                err("$", Code.LIMIT_EXCEEDED, "too many conditions: " + conditionCount + " > " + limits.maxConditions());

            Severity severity = enumValue(root, "severity", "$", true, Severity.class, null);
            EvidenceLevel evidenceLevel = enumValue(root, "evidenceLevel", "$", false, EvidenceLevel.class, EvidenceLevel.ANOMALY);
            if (evidenceLevel == EvidenceLevel.CONFIRMED_BY_SOURCE && !sourceBackedFieldUsed)
                err("$.evidenceLevel", Code.CONFLICT,
                        "CONFIRMED_BY_SOURCE requires a condition on a source-backed field (e.g. wallet.sanctioned)");
            List<ActionSpec> actions = actions(root.get("actions"), "$.actions");
            Duration cooldown = root.has("cooldown") ? duration(root.get("cooldown"), "$.cooldown", true) : DEFAULT_COOLDOWN;
            if (cooldown != null && cooldown.compareTo(limits.maxCooldown()) > 0)
                err("$.cooldown", Code.LIMIT_EXCEEDED, "cooldown exceeds " + limits.maxCooldown());
            String dedupKey = str(root, "dedupKey", "$", false, DedupKeyTemplate.MAX_LENGTH);
            if (dedupKey == null) dedupKey = Rule.DEFAULT_DEDUP_KEY;
            else try { DedupKeyTemplate.parse(dedupKey, catalog); }
            catch (IllegalArgumentException e) { err("$.dedupKey", Code.INVALID_TEMPLATE, e.getMessage()); }
            String documentation = str(root, "documentation", "$", false, 64 * 1024);
            BigDecimal confidence = null;
            if (root.has("expectedConfidence")) {
                confidence = decimal(root.get("expectedConfidence"), "$.expectedConfidence");
                if (confidence != null && (confidence.signum() < 0 || confidence.compareTo(BigDecimal.ONE) > 0))
                    err("$.expectedConfidence", Code.INVALID_VALUE, "must be between 0 and 1");
            }
            List<String> tags = stringList(root.get("tags"), "$.tags", 20, SLUG);
            List<RuleExample> examples = examples(root.get("examples"), "$.examples");
            List<RuleTestCase> tests = tests(root.get("tests"), "$.tests");
            if (!errors.isEmpty()) return null;
            return new Rule(api, id, version, name, description, author, enabled, scope, conditions, aggregation,
                    sequence, severity, evidenceLevel, actions, cooldown, dedupKey, documentation, confidence, tags,
                    examples, tests);
        }

        // ------------------------------------------------------------ scope

        Scope scope(JsonNode n, String path) {
            if (n == null) return new Scope(Set.of(), Set.of());
            if (!object(n, path)) return new Scope(Set.of(), Set.of());
            unknown(n, path, Set.of("networks", "eventTypes"));
            Set<Network> networks = EnumSet.noneOf(Network.class);
            for (String s : stringList(n.get("networks"), path + ".networks", Network.values().length, null)) {
                try { networks.add(Network.fromId(s)); }
                catch (IllegalArgumentException e) { err(path + ".networks", Code.INVALID_VALUE, "unknown network '" + s + "'"); }
            }
            Set<EventType> types = EnumSet.noneOf(EventType.class);
            for (String s : stringList(n.get("eventTypes"), path + ".eventTypes", EventType.values().length, null)) {
                try { types.add(EventType.valueOf(s)); }
                catch (IllegalArgumentException e) { err(path + ".eventTypes", Code.INVALID_VALUE, "unknown event type '" + s + "'"); }
            }
            return new Scope(networks, types);
        }

        // ------------------------------------------------------------ conditions

        Condition group(JsonNode n, String path, int depth) {
            if (depth > limits.maxDepth()) {
                err(path, Code.LIMIT_EXCEEDED, "conditions nested deeper than " + limits.maxDepth());
                return Group.matchAll();
            }
            if (!object(n, path)) return Group.matchAll();
            unknown(n, path, Set.of("logic", "rules", "groups"));
            Logic logic = enumValue(n, "logic", path, true, Logic.class, null);
            List<Condition> children = new ArrayList<>();
            JsonNode rules = n.get("rules");
            if (rules != null) {
                if (!rules.isArray()) err(path + ".rules", Code.TYPE, "must be an array");
                else for (int i = 0; i < rules.size(); i++) {
                    Condition c = leaf(rules.get(i), path + ".rules[" + i + "]");
                    if (c != null) children.add(c);
                    if (conditionCount > limits.maxConditions()) return Group.matchAll();
                }
            }
            JsonNode groups = n.get("groups");
            if (groups != null) {
                if (!groups.isArray()) err(path + ".groups", Code.TYPE, "must be an array");
                else for (int i = 0; i < groups.size(); i++) {
                    children.add(group(groups.get(i), path + ".groups[" + i + "]", depth + 1));
                    if (conditionCount > limits.maxConditions()) return Group.matchAll();
                }
            }
            int count = (rules != null && rules.isArray() ? rules.size() : 0) + (groups != null && groups.isArray() ? groups.size() : 0);
            if (count == 0) err(path, Code.REQUIRED, "a condition group needs at least one rule or group");
            if (logic == Logic.NOT && count != 1) err(path, Code.INVALID_VALUE, "NOT groups take exactly one rule or group");
            return new Group(logic == null ? Logic.AND : logic, children);
        }

        Condition leaf(JsonNode n, String path) {
            conditionCount++;
            if (!object(n, path)) return null;
            unknown(n, path, Set.of("field", "operator", "value", "ignoreCase"));
            String field = str(n, "field", path, true, 256);
            Operator op = enumValue(n, "operator", path, true, Operator.class, null);
            boolean ignoreCase = bool(n, "ignoreCase", path, false);
            FieldDef def = fieldRef(field, path + ".field");
            if (def == null || op == null) return null;
            if (def.sourceBacked()) sourceBackedFieldUsed = true;
            if (!Operator.allowedFor(def.type()).contains(op)) {
                err(path + ".operator", Code.INVALID_OPERATOR, op + " is not applicable to " + def.type() + " field '" + field + "'");
                return null;
            }
            if (ignoreCase && (def.type() != FieldType.STRING || op == Operator.MATCHES || op == Operator.EXISTS))
                err(path + ".ignoreCase", Code.INVALID_VALUE, "ignoreCase applies to STRING equality/IN/CONTAINS/STARTS_WITH only");
            Object value = leafValue(n.get("value"), path + ".value", def, op);
            return value == null ? null : new Leaf(field, op, value, ignoreCase);
        }

        Object leafValue(JsonNode v, String path, FieldDef def, Operator op) {
            switch (op) {
                case EXISTS -> {
                    if (v == null) return Boolean.TRUE;
                    if (!v.isBoolean()) { err(path, Code.TYPE, "EXISTS takes a boolean (or no value)"); return null; }
                    return v.booleanValue();
                }
                case IN, NOT_IN -> {
                    if (v == null || !v.isArray() || v.isEmpty()) { err(path, Code.TYPE, op + " takes a non-empty array"); return null; }
                    if (v.size() > limits.maxListValues()) {
                        err(path, Code.LIMIT_EXCEEDED, "more than " + limits.maxListValues() + " values"); return null;
                    }
                    List<Object> out = new ArrayList<>();
                    for (int i = 0; i < v.size(); i++) {
                        Object o = scalar(v.get(i), path + "[" + i + "]", def.type());
                        if (o == null) return null;
                        out.add(o);
                    }
                    return List.copyOf(out);
                }
                case BETWEEN -> {
                    if (v == null || !v.isArray() || v.size() != 2) { err(path, Code.TYPE, "BETWEEN takes [low, high]"); return null; }
                    Object lo = scalar(v.get(0), path + "[0]", def.type());
                    Object hi = scalar(v.get(1), path + "[1]", def.type());
                    if (lo == null || hi == null) return null;
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    int cmp = ((Comparable) lo).compareTo(hi);
                    if (cmp > 0) { err(path, Code.INVALID_VALUE, "BETWEEN low bound is greater than high bound"); return null; }
                    return List.of(lo, hi);
                }
                case MATCHES -> {
                    if (v == null || !v.isString()) { err(path, Code.TYPE, "MATCHES takes a regex string"); return null; }
                    try {
                        SafeRegex.compile(v.stringValue(), limits.maxRegexLength(), limits.maxRegexInputLength(), limits.maxRegexSteps());
                    } catch (SafeRegex.UnsafePatternException e) {
                        err(path, Code.UNSAFE_REGEX, e.getMessage()); return null;
                    } catch (IllegalArgumentException e) {
                        err(path, Code.INVALID_REGEX, e.getMessage()); return null;
                    }
                    return v.stringValue();
                }
                case CONTAINS, STARTS_WITH -> {
                    Object s = scalar(v, path, FieldType.STRING);
                    if (s instanceof String str && str.isEmpty()) { err(path, Code.INVALID_VALUE, "must not be empty"); return null; }
                    return s;
                }
                default -> { return scalar(v, path, def.type()); }
            }
        }

        /** Typed scalar literal: String, BigDecimal, Boolean or Instant; null (with an error) when invalid. */
        Object scalar(JsonNode v, String path, FieldType type) {
            if (v == null || v.isNull()) { err(path, Code.REQUIRED, "value is required"); return null; }
            switch (type) {
                case STRING -> {
                    if (!v.isString()) { err(path, Code.TYPE, "expected a string (quote it in YAML)"); return null; }
                    if (v.stringValue().length() > limits.maxStringLength()) {
                        err(path, Code.LIMIT_EXCEEDED, "string longer than " + limits.maxStringLength()); return null;
                    }
                    return v.stringValue();
                }
                case DECIMAL, INTEGER -> {
                    BigDecimal d = decimal(v, path);
                    if (d == null) return null;
                    if (type == FieldType.INTEGER && d.signum() != 0 && d.stripTrailingZeros().scale() > 0) {
                        err(path, Code.TYPE, "expected an integer"); return null;
                    }
                    return d;
                }
                case BOOLEAN -> {
                    if (!v.isBoolean()) { err(path, Code.TYPE, "expected a boolean"); return null; }
                    return v.booleanValue();
                }
                case TIMESTAMP -> {
                    if (!v.isString()) { err(path, Code.TYPE, "expected an ISO-8601 instant string"); return null; }
                    try { return Instant.parse(v.stringValue()); }
                    catch (DateTimeParseException e) { err(path, Code.TYPE, "expected an ISO-8601 instant"); return null; }
                }
            }
            return null;
        }

        BigDecimal decimal(JsonNode v, String path) {
            BigDecimal d = null;
            if (v != null && v.isNumber()) d = v.isBigDecimal() ? v.decimalValue() : Values.parseDecimal(v.asString());
            else if (v != null && v.isString()) d = Values.parseDecimal(v.stringValue());
            if (d == null) err(path, Code.TYPE, "expected an exact decimal number (number or numeric string)");
            else if (d.precision() > 80 || Math.abs(d.scale()) > 80) { err(path, Code.LIMIT_EXCEEDED, "number too large/precise"); d = null; }
            return d;
        }

        FieldDef fieldRef(String field, String path) {
            if (field == null) return null;
            FieldDef def = catalog.find(field).orElse(null);
            if (def == null) err(path, Code.UNKNOWN_FIELD, "unknown field '" + field + "' (not in the field catalog)");
            return def;
        }

        // ------------------------------------------------------------ aggregation / sequence

        Aggregation aggregation(JsonNode n, String path) {
            if (!object(n, path)) return null;
            unknown(n, path, Set.of("window", "groupBy", "function", "field", "balanceField", "operator", "threshold"));
            Duration window = n.has("window") ? duration(n.get("window"), path + ".window", false) : null;
            if (!n.has("window")) err(path + ".window", Code.REQUIRED, "window is required");
            checkWindow(window, path + ".window");
            List<String> groupBy = new ArrayList<>();
            JsonNode gb = n.get("groupBy");
            if (gb != null && gb.isString()) groupBy.add(gb.stringValue());
            else groupBy.addAll(stringList(gb, path + ".groupBy", limits.maxGroupByFields(), null));
            for (int i = 0; i < groupBy.size(); i++) fieldRef(groupBy.get(i), path + ".groupBy[" + i + "]");
            if (new LinkedHashSet<>(groupBy).size() != groupBy.size()) err(path + ".groupBy", Code.INVALID_VALUE, "duplicate groupBy field");
            AggregationFunction fn = enumValue(n, "function", path, true, AggregationFunction.class, null);
            String field = str(n, "field", path, false, 256);
            if (fn != null) {
                if (fn.needsField() && field == null) err(path + ".field", Code.REQUIRED, fn + " requires a field");
                if (!fn.needsField() && field != null) err(path + ".field", Code.INVALID_VALUE, "COUNT takes no field");
            }
            FieldDef fd = fieldRef(field, path + ".field");
            if (fn != null && fd != null && fn.needsNumericField() && !fd.type().numeric())
                err(path + ".field", Code.TYPE, fn + " requires a numeric field, '" + field + "' is " + fd.type());
            String balanceField = str(n, "balanceField", path, false, 256);
            if (fn == AggregationFunction.RATIO_OF_BALANCE) {
                if (balanceField == null) balanceField = DEFAULT_BALANCE_FIELD;
                FieldDef bd = fieldRef(balanceField, path + ".balanceField");
                if (bd != null && !bd.type().numeric()) err(path + ".balanceField", Code.TYPE, "balance field must be numeric");
            } else if (balanceField != null) {
                err(path + ".balanceField", Code.INVALID_VALUE, "balanceField only applies to RATIO_OF_BALANCE");
            }
            Operator op = enumValue(n, "operator", path, true, Operator.class, null);
            if (op != null && op != Operator.GREATER_THAN && op != Operator.GREATER_THAN_OR_EQUAL)
                err(path + ".operator", Code.INVALID_OPERATOR, "aggregation thresholds support GREATER_THAN and GREATER_THAN_OR_EQUAL");
            BigDecimal threshold = null;
            if (!n.has("threshold")) err(path + ".threshold", Code.REQUIRED, "threshold is required");
            else {
                threshold = decimal(n.get("threshold"), path + ".threshold");
                if (threshold != null && threshold.signum() < 0) err(path + ".threshold", Code.INVALID_VALUE, "threshold must be >= 0");
            }
            return new Aggregation(window, groupBy, fn, field, balanceField, op, threshold);
        }

        Sequence sequence(JsonNode n, String path) {
            if (!object(n, path)) return null;
            unknown(n, path, Set.of("key", "within", "steps"));
            String key = str(n, "key", path, true, 256);
            fieldRef(key, path + ".key");
            Duration within = n.has("within") ? duration(n.get("within"), path + ".within", false) : null;
            if (!n.has("within")) err(path + ".within", Code.REQUIRED, "within is required");
            checkWindow(within, path + ".within");
            List<SequenceStep> steps = new ArrayList<>();
            JsonNode s = n.get("steps");
            if (s == null || !s.isArray() || s.size() < 2) err(path + ".steps", Code.REQUIRED, "a sequence needs at least 2 steps");
            else if (s.size() > limits.maxSequenceSteps()) err(path + ".steps", Code.LIMIT_EXCEEDED, "more than " + limits.maxSequenceSteps() + " steps");
            else for (int i = 0; i < s.size(); i++) {
                String sp = path + ".steps[" + i + "]";
                JsonNode step = s.get(i);
                if (!object(step, sp)) continue;
                unknown(step, sp, Set.of("name", "conditions"));
                String name = pattern(step, "name", sp, true, SLUG, "slug");
                if (!step.has("conditions")) { err(sp + ".conditions", Code.REQUIRED, "step conditions are required"); continue; }
                steps.add(new SequenceStep(name, group(step.get("conditions"), sp + ".conditions", 1)));
            }
            return new Sequence(key, within, steps);
        }

        void checkWindow(Duration d, String path) {
            if (d == null) return;
            if (d.compareTo(limits.maxWindow()) > 0) err(path, Code.LIMIT_EXCEEDED, "window exceeds maximum of " + limits.maxWindow());
            if (d.compareTo(limits.minWindow()) < 0) err(path, Code.INVALID_VALUE, "window shorter than " + limits.minWindow());
        }

        /** {duration, unit} | {preset} | "5m". */
        Duration duration(JsonNode n, String path, boolean allowZero) {
            if (n == null) return null;
            if (n.isString()) return shortDuration(n.stringValue(), path);
            if (!object(n, path)) return null;
            unknown(n, path, Set.of("duration", "unit", "preset"));
            if (n.has("preset")) {
                if (n.has("duration") || n.has("unit")) err(path, Code.CONFLICT, "use either preset or duration+unit");
                return shortDuration(n.get("preset").asString(), path + ".preset");
            }
            JsonNode d = n.get("duration");
            if (d == null || !d.isIntegralNumber() || !d.canConvertToLong()) { err(path + ".duration", Code.TYPE, "duration must be an integer"); return null; }
            long amount = d.longValue();
            if (amount < 0 || (amount == 0 && !allowZero) || amount > 10_000_000) {
                err(path + ".duration", Code.INVALID_VALUE, "duration out of range"); return null;
            }
            String unit = str(n, "unit", path, true, 16);
            if (unit == null) return null;
            return switch (unit.toUpperCase(Locale.ROOT)) {
                case "SECONDS" -> Duration.ofSeconds(amount);
                case "MINUTES" -> Duration.ofMinutes(amount);
                case "HOURS" -> Duration.ofHours(amount);
                case "DAYS" -> Duration.ofDays(amount);
                default -> { err(path + ".unit", Code.INVALID_VALUE, "unit must be SECONDS|MINUTES|HOURS|DAYS"); yield null; }
            };
        }

        Duration shortDuration(String s, String path) {
            Matcher m = s == null ? null : DURATION_SHORT.matcher(s);
            if (m == null || !m.matches()) { err(path, Code.PATTERN, "expected a duration like 30s, 5m, 1h, 24h, 7d"); return null; }
            long a = Long.parseLong(m.group(1));
            return switch (m.group(2)) {
                case "s" -> Duration.ofSeconds(a);
                case "m" -> Duration.ofMinutes(a);
                case "h" -> Duration.ofHours(a);
                default -> Duration.ofDays(a);
            };
        }

        // ------------------------------------------------------------ actions

        List<ActionSpec> actions(JsonNode n, String path) {
            if (n == null) return List.of();
            if (!n.isArray()) { err(path, Code.TYPE, "must be an array"); return List.of(); }
            if (n.size() > limits.maxActions()) { err(path, Code.LIMIT_EXCEEDED, "more than " + limits.maxActions() + " actions"); return List.of(); }
            List<ActionSpec> out = new ArrayList<>();
            for (int i = 0; i < n.size(); i++) {
                String p = path + "[" + i + "]";
                JsonNode a = n.get(i);
                if (!object(a, p)) continue;
                unknown(a, p, Set.of("type", "connectorId", "params"));
                ActionType type = enumValue(a, "type", p, true, ActionType.class, null);
                String connector = pattern(a, "connectorId", p, true, SLUG, "slug");
                Map<String, String> params = new LinkedHashMap<>();
                JsonNode pn = a.get("params");
                if (pn != null && object(pn, p + ".params")) {
                    if (pn.size() > limits.maxParams()) err(p + ".params", Code.LIMIT_EXCEEDED, "more than " + limits.maxParams() + " params");
                    for (Map.Entry<String, JsonNode> e : pn.properties()) {
                        String kp = p + ".params." + e.getKey();
                        if (!PARAM_KEY.matcher(e.getKey()).matches()) { err(kp, Code.PATTERN, "invalid param name"); continue; }
                        JsonNode v = e.getValue();
                        if (!(v.isString() || v.isNumber() || v.isBoolean())) { err(kp, Code.TYPE, "params must be scalars"); continue; }
                        String sv = v.asString();
                        if (sv.length() > limits.maxStringLength()) { err(kp, Code.LIMIT_EXCEEDED, "param too long"); continue; }
                        params.put(e.getKey(), sv);
                    }
                }
                if (type != null && connector != null) out.add(new ActionSpec(type, connector, params));
            }
            return out;
        }

        // ------------------------------------------------------------ examples & tests

        List<RuleExample> examples(JsonNode n, String path) {
            if (n == null) return List.of();
            if (!n.isArray()) { err(path, Code.TYPE, "must be an array"); return List.of(); }
            if (n.size() > limits.maxExamples()) { err(path, Code.LIMIT_EXCEEDED, "too many examples"); return List.of(); }
            List<RuleExample> out = new ArrayList<>();
            for (int i = 0; i < n.size(); i++) {
                String p = path + "[" + i + "]";
                JsonNode ex = n.get(i);
                if (!object(ex, p)) continue;
                unknown(ex, p, Set.of("name", "expect", "event"));
                String name = str(ex, "name", p, true, 200);
                String expect = str(ex, "expect", p, true, 16);
                if (expect != null && !expect.equals("MATCH") && !expect.equals("NO_MATCH"))
                    err(p + ".expect", Code.INVALID_VALUE, "expect must be MATCH or NO_MATCH");
                if (!ex.has("event")) { err(p + ".event", Code.REQUIRED, "event is required"); continue; }
                List<TestEvent> evs = testEvents(ex.get("event"), p + ".event", "example-" + i, 0, false);
                if (name != null && expect != null && evs.size() == 1)
                    out.add(new RuleExample(name, "MATCH".equals(expect), evs.getFirst()));
            }
            return out;
        }

        List<RuleTestCase> tests(JsonNode n, String path) {
            if (n == null) return List.of();
            if (!n.isArray()) { err(path, Code.TYPE, "must be an array"); return List.of(); }
            if (n.size() > limits.maxTests()) { err(path, Code.LIMIT_EXCEEDED, "too many tests"); return List.of(); }
            List<RuleTestCase> out = new ArrayList<>();
            for (int i = 0; i < n.size(); i++) {
                String p = path + "[" + i + "]";
                JsonNode t = n.get(i);
                if (!object(t, p)) continue;
                unknown(t, p, Set.of("name", "events", "expectTriggers", "expectAlerts"));
                String name = str(t, "name", p, true, 200);
                Integer triggers = nonNegativeInt(t, "expectTriggers", p, true);
                Integer alerts = nonNegativeInt(t, "expectAlerts", p, false);
                JsonNode evs = t.get("events");
                if (evs == null || !evs.isArray() || evs.isEmpty()) { err(p + ".events", Code.REQUIRED, "events must be a non-empty array"); continue; }
                List<TestEvent> events = new ArrayList<>();
                for (int j = 0; j < evs.size() && events.size() <= limits.maxTestEvents(); j++)
                    events.addAll(testEvents(evs.get(j), p + ".events[" + j + "]", "test-" + i + "-" + j, events.size(), true));
                if (events.size() > limits.maxTestEvents()) { err(p + ".events", Code.LIMIT_EXCEEDED, "more than " + limits.maxTestEvents() + " events"); continue; }
                if (name != null && triggers != null) out.add(new RuleTestCase(name, events, triggers, alerts));
            }
            return out;
        }

        List<TestEvent> testEvents(JsonNode n, String path, String idPrefix, int already, boolean allowRepeat) {
            if (!object(n, path)) return List.of();
            Set<String> allowed = allowRepeat
                    ? Set.of("eventId", "type", "network", "time", "offset", "block", "removed", "fields", "repeat", "every")
                    : Set.of("eventId", "type", "network", "time", "offset", "block", "fields");
            unknown(n, path, allowed);
            EventType type = enumValue(n, "type", path, false, EventType.class, null);
            if (type == null && !n.has("type")) {
                if (scope.eventTypes().size() == 1) type = scope.eventTypes().iterator().next();
                else err(path + ".type", Code.REQUIRED, "type is required when the rule scope has not exactly one event type");
            }
            Network network = null;
            String net = str(n, "network", path, false, 32);
            if (net != null) {
                try { network = Network.fromId(net); } catch (IllegalArgumentException e) { err(path + ".network", Code.INVALID_VALUE, "unknown network"); }
            } else if (scope.networks().size() == 1) network = scope.networks().iterator().next();
            else err(path + ".network", Code.REQUIRED, "network is required when the rule scope has not exactly one network");
            Instant base = TEST_EPOCH;
            if (n.has("time")) {
                try { base = Instant.parse(n.get("time").asString()); }
                catch (DateTimeParseException e) { err(path + ".time", Code.TYPE, "expected an ISO-8601 instant"); }
            }
            if (n.has("offset")) base = base.plus(offsetOf(n.get("offset"), path + ".offset"));
            long block = n.has("block") ? n.get("block").asLong(0) : 0;
            boolean removed = bool(n, "removed", path, false);
            int repeat = 1;
            if (n.has("repeat")) {
                Integer r = nonNegativeInt(n, "repeat", path, true);
                repeat = r == null ? 1 : r;
                if (repeat < 1 || already + repeat > limits.maxTestEvents()) {
                    err(path + ".repeat", Code.LIMIT_EXCEEDED, "repeat must be 1.." + (limits.maxTestEvents() - already)); return List.of();
                }
            }
            Duration every = n.has("every") ? offsetOf(n.get("every"), path + ".every") : Duration.ZERO;
            String explicitId = str(n, "eventId", path, false, 256);
            JsonNode fieldsNode = n.get("fields");
            if (fieldsNode != null && !object(fieldsNode, path + ".fields")) return List.of();
            int errorsBefore = errors.size();
            List<TestEvent> out = new ArrayList<>();
            for (int i = 0; i < repeat; i++) {
                Map<String, Object> fields = new LinkedHashMap<>();
                if (fieldsNode != null) for (Map.Entry<String, JsonNode> e : fieldsNode.properties()) {
                    String fp = path + ".fields." + e.getKey();
                    FieldDef def = catalog.find(e.getKey()).orElse(null);
                    if (def == null) { if (i == 0) err(fp, Code.UNKNOWN_FIELD, "unknown field '" + e.getKey() + "'"); continue; }
                    if (e.getKey().equals("network") || e.getKey().startsWith("event.")) {
                        if (i == 0) err(fp, Code.INVALID_VALUE, "envelope field; use type/network/time/block instead"); continue;
                    }
                    JsonNode v = e.getValue();
                    if (v.isString() && v.stringValue().contains("{{i}}"))
                        v = tools.jackson.databind.node.JsonNodeFactory.instance.stringNode(v.stringValue().replace("{{i}}", Integer.toString(i)));
                    Object typed = scalar(v, fp, def.type());
                    if (typed instanceof BigDecimal d && def.type() == FieldType.INTEGER && d.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                            && d.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0) typed = d.longValueExact();
                    if (typed != null) fields.put(e.getKey(), typed);
                }
                if (errors.size() > errorsBefore) return List.of();
                String id = explicitId != null ? explicitId.replace("{{i}}", Integer.toString(i))
                        : idPrefix + (repeat > 1 ? "-" + i : "");
                out.add(new TestEvent(id, type, network, block + i, base.plus(every.multipliedBy(i)), removed, fields));
            }
            return (type == null || network == null) ? List.of() : out;
        }

        Duration offsetOf(JsonNode n, String path) {
            if (n != null && n.isString() && n.stringValue().startsWith("P")) {
                try { return Duration.parse(n.stringValue()); }
                catch (DateTimeParseException e) { err(path, Code.TYPE, "invalid ISO-8601 duration"); return Duration.ZERO; }
            }
            if (n != null && n.isString() && n.stringValue().matches("^0[smhd]?$")) return Duration.ZERO;
            Duration d = duration(n, path, true);
            return d == null ? Duration.ZERO : d;
        }

        // ------------------------------------------------------------ primitives

        boolean object(JsonNode n, String path) {
            if (n == null || !n.isObject()) { err(path, Code.TYPE, "must be an object"); return false; }
            return true;
        }

        void unknown(JsonNode n, String path, Set<String> allowed) {
            for (String k : n.propertyNames())
                if (!allowed.contains(k)) err(path + "." + k, Code.UNKNOWN_PROPERTY, "unknown property '" + k + "'");
        }

        String str(JsonNode parent, String key, String path, boolean required, int maxLen) {
            JsonNode v = parent.get(key);
            String p = path + "." + key;
            if (v == null || v.isNull()) { if (required) err(p, Code.REQUIRED, key + " is required"); return null; }
            if (!v.isString()) { err(p, Code.TYPE, key + " must be a string"); return null; }
            String s = v.stringValue();
            if (required && s.isBlank()) { err(p, Code.REQUIRED, key + " must not be blank"); return null; }
            if (s.length() > maxLen) { err(p, Code.LIMIT_EXCEEDED, key + " longer than " + maxLen); return null; }
            return s;
        }

        String pattern(JsonNode parent, String key, String path, boolean required, Pattern pat, String expected) {
            String s = str(parent, key, path, required, 256);
            if (s != null && !pat.matcher(s).matches()) { err(path + "." + key, Code.PATTERN, key + " must be a " + expected); return null; }
            return s;
        }

        boolean bool(JsonNode parent, String key, String path, boolean dflt) {
            JsonNode v = parent.get(key);
            if (v == null) return dflt;
            if (!v.isBoolean()) { err(path + "." + key, Code.TYPE, key + " must be a boolean"); return dflt; }
            return v.booleanValue();
        }

        Integer nonNegativeInt(JsonNode parent, String key, String path, boolean required) {
            JsonNode v = parent.get(key);
            if (v == null) { if (required) err(path + "." + key, Code.REQUIRED, key + " is required"); return null; }
            if (!v.isIntegralNumber() || !v.canConvertToInt() || v.intValue() < 0) {
                err(path + "." + key, Code.TYPE, key + " must be a non-negative integer"); return null;
            }
            return v.intValue();
        }

        <E extends Enum<E>> E enumValue(JsonNode parent, String key, String path, boolean required, Class<E> type, E dflt) {
            JsonNode v = parent.get(key);
            if (v == null) { if (required && dflt == null) err(path + "." + key, Code.REQUIRED, key + " is required"); return dflt; }
            if (!v.isString()) { err(path + "." + key, Code.TYPE, key + " must be a string"); return null; }
            try { return Enum.valueOf(type, v.stringValue()); }
            catch (IllegalArgumentException e) {
                err(path + "." + key, Code.INVALID_VALUE, "invalid " + key + " '" + v.stringValue() + "', expected one of "
                        + EnumSet.allOf(type));
                return null;
            }
        }

        List<String> stringList(JsonNode n, String path, int max, Pattern pat) {
            if (n == null) return List.of();
            if (!n.isArray()) { err(path, Code.TYPE, "must be an array of strings"); return List.of(); }
            if (n.size() > max) { err(path, Code.LIMIT_EXCEEDED, "more than " + max + " entries"); return List.of(); }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < n.size(); i++) {
                JsonNode v = n.get(i);
                if (!v.isString() || v.stringValue().length() > 256) { err(path + "[" + i + "]", Code.TYPE, "must be a string"); continue; }
                if (pat != null && !pat.matcher(v.stringValue()).matches()) { err(path + "[" + i + "]", Code.PATTERN, "invalid value"); continue; }
                out.add(v.stringValue());
            }
            return out;
        }
    }
}
