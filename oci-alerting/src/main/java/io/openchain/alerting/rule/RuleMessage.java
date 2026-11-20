package io.openchain.alerting.rule;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every rule validation message: a stable {@code key} (what clients translate) and the English template with
 * {@code {param}} placeholders (what {@link ValidationError#message()} keeps saying). One entry per distinct template.
 */
public enum RuleMessage {

    // ---- document (parser) ---------------------------------------------------------------------------------------
    EMPTY_DOCUMENT("rule.empty_document", "empty document"),
    DOCUMENT_TOO_LARGE("rule.document_too_large", "document larger than {max} bytes"),
    DOCUMENT_NOT_OBJECT("rule.document_not_object", "rule document must be an object/mapping"),
    MALFORMED_DOCUMENT("rule.malformed_document", "malformed {format}: {reason}"),
    MALFORMED_YAML("rule.malformed_yaml", "malformed YAML: {reason}"),
    YAML_ALIASES("rule.yaml_aliases", "YAML aliases are not allowed (line {line}, column {column})"),
    YAML_MULTIPLE_DOCUMENTS("rule.yaml_multiple_documents", "only one YAML document is allowed (line {line}, column {column})"),
    YAML_NESTED_TOO_DEEPLY("rule.yaml_nested_too_deeply", "document nested too deeply (line {line}, column {column})"),
    YAML_ANCHORS("rule.yaml_anchors", "YAML anchors are not allowed (line {line}, column {column})"),
    YAML_TAGS("rule.yaml_tags", "explicit YAML tags are not allowed: {tag} (line {line}, column {column})"),

    // ---- rule ------------------------------------------------------------------------------------------------------
    NOT_OBJECT("rule.not_object", "rule must be an object"),
    UNSUPPORTED_API_VERSION("rule.unsupported_api_version", "unsupported apiVersion '{found}', expected {expected}"),
    CONDITIONS_REQUIRED("rule.conditions_required", "conditions are required (except for sequence rules)"),
    AGGREGATION_AND_SEQUENCE("rule.aggregation_and_sequence", "a rule cannot declare both aggregation and sequence"),
    TOO_MANY_CONDITIONS("rule.too_many_conditions", "too many conditions: {count} > {max}"),
    CONFIRMED_NEEDS_SOURCE_FIELD("rule.confirmed_needs_source_field",
            "CONFIRMED_BY_SOURCE requires a condition on a source-backed field (e.g. wallet.sanctioned)"),
    COOLDOWN_TOO_LONG("rule.cooldown_too_long", "cooldown exceeds {max}"),
    INVALID_DEDUP_TEMPLATE("rule.invalid_dedup_template", "{reason}"),
    CONFIDENCE_OUT_OF_RANGE("rule.confidence_out_of_range", "must be between 0 and 1"),
    ID_MISMATCH("rule.id_differs_from_path", "id '{found}' does not match '{expected}'"),
    GENERIC("rule.generic", "{reason}"),

    // ---- scope -----------------------------------------------------------------------------------------------------
    UNKNOWN_NETWORK("rule.unknown_network", "unknown network '{network}'"),
    UNKNOWN_NETWORK_PLAIN("rule.unknown_network_plain", "unknown network"),
    UNKNOWN_EVENT_TYPE("rule.unknown_event_type", "unknown event type '{type}'"),

    // ---- conditions ------------------------------------------------------------------------------------------------
    NESTED_TOO_DEEPLY("rule.nested_too_deeply", "conditions nested deeper than {max}"),
    GROUP_EMPTY("rule.group_empty", "a condition group needs at least one rule or group"),
    NOT_GROUP_ARITY("rule.not_group_arity", "NOT groups take exactly one rule or group"),
    OPERATOR_NOT_ALLOWED("rule.operator_not_allowed", "{operator} is not applicable to {type} field '{field}'"),
    IGNORE_CASE_SCOPE("rule.ignore_case_scope", "ignoreCase applies to STRING equality/IN/CONTAINS/STARTS_WITH only"),
    EXISTS_TAKES_BOOLEAN("rule.exists_takes_boolean", "EXISTS takes a boolean (or no value)"),
    OPERATOR_TAKES_ARRAY("rule.operator_takes_array", "{operator} takes a non-empty array"),
    TOO_MANY_VALUES("rule.too_many_values", "more than {max} values"),
    BETWEEN_TAKES_PAIR("rule.between_takes_pair", "BETWEEN takes [low, high]"),
    BETWEEN_BOUNDS_REVERSED("rule.between_bounds_reversed", "BETWEEN low bound is greater than high bound"),
    MATCHES_TAKES_STRING("rule.matches_takes_string", "MATCHES takes a regex string"),
    UNSAFE_REGEX("rule.unsafe_regex", "{reason}"),
    INVALID_REGEX("rule.invalid_regex", "{reason}"),
    MUST_NOT_BE_EMPTY("rule.must_not_be_empty", "must not be empty"),
    VALUE_REQUIRED("rule.value_required", "value is required"),
    EXPECTED_STRING("rule.expected_string", "expected a string (quote it in YAML)"),
    STRING_TOO_LONG("rule.string_too_long", "string longer than {max}"),
    EXPECTED_INTEGER("rule.expected_integer", "expected an integer"),
    EXPECTED_BOOLEAN("rule.expected_boolean", "expected a boolean"),
    EXPECTED_INSTANT_STRING("rule.expected_instant_string", "expected an ISO-8601 instant string"),
    EXPECTED_INSTANT("rule.expected_instant", "expected an ISO-8601 instant"),
    EXPECTED_DECIMAL("rule.expected_decimal", "expected an exact decimal number (number or numeric string)"),
    NUMBER_TOO_PRECISE("rule.number_too_precise", "number too large/precise"),
    UNKNOWN_FIELD("rule.unknown_field", "unknown field '{field}' (not in the field catalog)"),
    UNKNOWN_FIELD_PLAIN("rule.unknown_field_plain", "unknown field '{field}'"),

    // ---- aggregation -----------------------------------------------------------------------------------------------
    DUPLICATE_GROUP_BY("rule.duplicate_group_by", "duplicate groupBy field"),
    FUNCTION_NEEDS_FIELD("rule.function_needs_field", "{function} requires a field"),
    COUNT_TAKES_NO_FIELD("rule.count_takes_no_field", "COUNT takes no field"),
    FIELD_NOT_NUMERIC("rule.field_not_numeric", "{function} requires a numeric field, '{field}' is {type}"),
    BALANCE_FIELD_NOT_NUMERIC("rule.balance_field_not_numeric", "balance field must be numeric"),
    BALANCE_FIELD_MISPLACED("rule.balance_field_misplaced", "balanceField only applies to RATIO_OF_BALANCE"),
    THRESHOLD_OPERATOR_UNSUPPORTED("rule.threshold_operator_unsupported",
            "aggregation thresholds support GREATER_THAN and GREATER_THAN_OR_EQUAL"),
    THRESHOLD_NEGATIVE("rule.threshold_negative", "threshold must be >= 0"),

    // ---- sequence / windows ----------------------------------------------------------------------------------------
    STEPS_MINIMUM("rule.steps_minimum", "a sequence needs at least 2 steps"),
    TOO_MANY_STEPS("rule.too_many_steps", "more than {max} steps"),
    STEP_CONDITIONS_REQUIRED("rule.step_conditions_required", "step conditions are required"),
    WINDOW_TOO_LONG("rule.window_too_long", "window exceeds maximum of {max}"),
    WINDOW_TOO_SHORT("rule.window_too_short", "window shorter than {min}"),
    PRESET_OR_DURATION("rule.preset_or_duration", "use either preset or duration+unit"),
    DURATION_NOT_INTEGER("rule.duration_not_integer", "duration must be an integer"),
    DURATION_OUT_OF_RANGE("rule.duration_out_of_range", "duration out of range"),
    UNIT_INVALID("rule.unit_invalid", "unit must be SECONDS|MINUTES|HOURS|DAYS"),
    DURATION_PATTERN("rule.duration_pattern", "expected a duration like 30s, 5m, 1h, 24h, 7d"),
    INVALID_ISO_DURATION("rule.invalid_iso_duration", "invalid ISO-8601 duration"),

    // ---- actions, examples, tests ----------------------------------------------------------------------------------
    TOO_MANY_ACTIONS("rule.too_many_actions", "more than {max} actions"),
    TOO_MANY_PARAMS("rule.too_many_params", "more than {max} params"),
    PARAM_NAME_INVALID("rule.param_name_invalid", "invalid param name"),
    PARAMS_NOT_SCALAR("rule.params_not_scalar", "params must be scalars"),
    PARAM_TOO_LONG("rule.param_too_long", "param too long"),
    TOO_MANY_EXAMPLES("rule.too_many_examples", "too many examples"),
    TOO_MANY_TESTS("rule.too_many_tests", "too many tests"),
    EXPECT_INVALID("rule.expect_invalid", "expect must be MATCH or NO_MATCH"),
    EVENTS_REQUIRED("rule.events_required", "events must be a non-empty array"),
    TOO_MANY_EVENTS("rule.too_many_events", "more than {max} events"),
    EVENT_TYPE_REQUIRED("rule.event_type_required", "type is required when the rule scope has not exactly one event type"),
    EVENT_NETWORK_REQUIRED("rule.event_network_required", "network is required when the rule scope has not exactly one network"),
    REPEAT_RANGE("rule.repeat_range", "repeat must be 1..{max}"),
    ENVELOPE_FIELD("rule.envelope_field", "envelope field; use type/network/time/block instead"),

    // ---- generic shapes --------------------------------------------------------------------------------------------
    MUST_BE_OBJECT("rule.must_be_object", "must be an object"),
    MUST_BE_ARRAY("rule.must_be_array", "must be an array"),
    MUST_BE_ARRAY_OF_STRINGS("rule.must_be_array_of_strings", "must be an array of strings"),
    MUST_BE_STRING("rule.must_be_string", "must be a string"),
    TOO_MANY_ENTRIES("rule.too_many_entries", "more than {max} entries"),
    INVALID_VALUE("rule.invalid_value", "invalid value"),
    UNKNOWN_PROPERTY("rule.unknown_property", "unknown property '{property}'"),
    REQUIRED("rule.required", "{key} is required"),
    KEY_MUST_BE_STRING("rule.key_must_be_string", "{key} must be a string"),
    KEY_MUST_NOT_BE_BLANK("rule.key_must_not_be_blank", "{key} must not be blank"),
    KEY_TOO_LONG("rule.key_too_long", "{key} longer than {max}"),
    KEY_PATTERN("rule.key_pattern", "{key} must be a {expected}"),
    KEY_MUST_BE_BOOLEAN("rule.key_must_be_boolean", "{key} must be a boolean"),
    KEY_MUST_BE_NON_NEGATIVE_INTEGER("rule.key_must_be_non_negative_integer", "{key} must be a non-negative integer"),
    INVALID_ENUM_VALUE("rule.invalid_enum_value", "invalid {key} '{value}', expected one of {allowed}");

    private static final class Placeholder {
        static final Pattern PARAM = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");
    }

    private final String key;
    private final String template;
    private final Set<String> params;

    RuleMessage(String key, String template) {
        this.key = key;
        this.template = template;
        Set<String> p = new LinkedHashSet<>();
        Matcher m = Placeholder.PARAM.matcher(template);
        while (m.find()) p.add(m.group(1));
        this.params = Set.copyOf(p);
    }

    public String key() { return key; }
    public String template() { return template; }
    public Set<String> params() { return params; }

    /** The English message; the supplied params must be exactly the declared ones. */
    public String fill(Map<String, ?> values) {
        if (!values.keySet().equals(params))
            throw new IllegalArgumentException(key + " takes " + params + " but got " + values.keySet());
        Matcher m = Placeholder.PARAM.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(values.get(m.group(1)))));
        m.appendTail(out);
        return out.toString();
    }

    /** Builds the params map from alternating names and values. */
    static Map<String, Object> params(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("params come in name/value pairs");
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }
}
