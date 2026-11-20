package io.openchain.app.api;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stable machine codes of the API's errors. {@code id} is what clients translate; {@code template} is the English
 * sentence with {@code {param}} placeholders, kept as the {@code detail} / {@code message} for clients that do not.
 * The catalogue is published as {@code docs/api/codes.json}.
 */
public enum ApiCode {

    // ---- authentication / transport ------------------------------------------------------------------------------
    AUTH_CREDENTIALS_IN_URL("auth.credentials_in_url", "credentials must not be sent in the URL; use the X-API-Key or Authorization header"),
    AUTH_INVALID_KEY("auth.invalid_key", "invalid, expired or revoked API key"),
    AUTH_BEARER_UNSUPPORTED("auth.bearer_unsupported", "bearer tokens other than API keys require OIDC, which is not configured"),
    AUTH_MISSING_CREDENTIALS("auth.missing_credentials", "missing or invalid credentials"),
    AUTH_MISSING_PERMISSION("auth.missing_permission", "missing permission for this operation"),
    AUTH_INVALID_BEARER("auth.invalid_bearer", "invalid bearer token"),
    AUTH_MISSING_SCOPE("auth.missing_scope", "missing scope or role"),
    HTTP_NO_ENDPOINT("http.no_endpoint", "no endpoint {method} {uri}"),
    HTTP_MALFORMED_BODY("http.malformed_body", "malformed request body"),
    HTTP_ERROR("http.error", "{reason}"),
    ERROR_UNEXPECTED("error.unexpected", "An unexpected error occurred."),
    UPSTREAM_PROVIDER_UNAVAILABLE("upstream.provider_unavailable", "chain provider unavailable: {reason}"),

    // ---- messages that come from other components unchanged -----------------------------------------------------
    GENERIC_BAD_REQUEST("generic.bad_request", "{reason}"),
    GENERIC_NOT_FOUND("generic.not_found", "{reason}"),
    GENERIC_CONFLICT("generic.conflict", "{reason}"),
    GENERIC_FORBIDDEN("generic.forbidden", "{reason}"),
    GENERIC_TOO_MANY_REQUESTS("generic.too_many_requests", "{reason}"),

    // ---- request shape --------------------------------------------------------------------------------------------
    BODY_REQUIRED("body.required", "body is required"),
    BODY_REQUEST_REQUIRED("body.request_required", "request body is required"),
    BODY_MALFORMED_JSON("body.malformed_json", "malformed JSON body"),
    BODY_NOT_OBJECT("body.not_object", "JSON body must be an object"),
    PARAM_OUT_OF_RANGE("param.out_of_range", "{param} must be between {min} and {max}"),
    PARAM_INVALID_TIMESTAMP("param.invalid_timestamp", "'{name}' must be an RFC 3339 timestamp"),
    PARAM_FROM_AFTER_TO("param.from_after_to", "'from' must be before 'to'"),
    PARAM_DIRECTION_INVALID("param.direction_invalid", "direction must be out, in or both"),
    PAGE_INVALID_CURSOR("page.invalid_cursor", "invalid cursor"),

    // ---- networks, wallets, transactions --------------------------------------------------------------------------
    NETWORK_REQUIRED("network.required", "query parameter 'network' is required"),
    NETWORK_UNKNOWN("network.unknown", "unknown network '{network}'"),
    NETWORK_NOT_ENABLED("network.not_enabled", "network '{network}' is not enabled on this instance"),
    NETWORK_NOT_ENABLED_GENERIC("network.not_enabled_generic", "network not enabled"),
    NETWORK_REQUIRED_WITH_ADDRESS("network.required_with_address", "'network' is required with 'address'"),
    WALLET_INVALID_ADDRESS("wallet.invalid_address", "invalid address"),
    TX_INVALID_HASH("tx.invalid_hash", "invalid transaction hash"),
    TX_NOT_FOUND("tx.not_found", "transaction {hash} not found on {network}"),
    LABEL_UNKNOWN_CATEGORY("label.unknown_category", "unknown label category '{category}'"),
    LABEL_REPORT_INVALID("label.report_invalid", "label report is invalid"),
    GRAPH_EVENT_IDS_REQUIRED("graph.event_ids_required", "eventIds is required"),
    GRAPH_TOO_MANY_EVENT_IDS("graph.too_many_event_ids", "at most {max} eventIds"),
    GRAPH_INVALID_EVENT_ID("graph.invalid_event_id", "invalid eventId"),
    SIMULATION_NO_EXPOSURE("simulation.no_exposure", "category {category} creates no exposure"),
    PREDICTION_OUTCOME_INVALID("prediction.outcome_invalid", "outcome must be OPEN, REALISED, NOT_REALISED or UNDETERMINED"),

    // ---- alerts ----------------------------------------------------------------------------------------------------
    ALERT_NOT_FOUND("alert.not_found", "alert {id} does not exist"),
    ALERT_STATUS_REQUIRED("alert.status_required", "status is required"),
    ALERT_STATUS_UNKNOWN("alert.status_unknown", "unknown status '{status}'"),
    ALERT_REOPEN_UNSUPPORTED("alert.reopen_unsupported",
            "re-opening alerts is not supported: RESOLVED and FALSE_POSITIVE are terminal; a new trigger opens a new alert"),
    ALERT_ALREADY_IN_STATUS("alert.already_in_status", "alert is already {status}"),
    ALERT_ASSIGNEE_TOO_LONG("alert.assignee_too_long", "assignee too long"),
    ALERT_COMMENT_EMPTY("alert.comment_empty", "comment body must not be empty"),
    ALERT_COMMENT_TOO_LONG("alert.comment_too_long", "comment body is limited to {max} characters"),

    // ---- rules -----------------------------------------------------------------------------------------------------
    RULE_NOT_FOUND("rule.not_found", "rule '{id}' does not exist"),
    RULE_VERSION_NOT_FOUND("rule.version_not_found", "rule {id} has no version {version}"),
    RULE_INVALID("rule.invalid", "{reason}"),
    RULE_SOURCE_INVALID("rule.source_invalid", "Rule source is invalid"),
    RULE_SOURCE_REQUIRED("rule.source_required", "'source' (or 'yaml') is required"),
    RULE_PROPOSED_SOURCE_REQUIRED("rule.proposed_source_required", "'source' (the proposed rule) is required"),
    RULE_ID_MISMATCH("rule.id_mismatch", "the proposed source must keep the rule id '{id}'"),
    RULE_MODE_UNSUPPORTED("rule.mode_unsupported", "only mode DRY_RUN is supported"),
    RULE_BODY_SHAPE("rule.body_shape", "expected {source}, {yaml} or a JSON rule document"),
    RULE_IMPORT_EMPTY("rule.import_empty", "empty import"),
    RULE_IMPORT_TOO_MANY("rule.import_too_many", "at most {max} rules per import"),
    RULE_IMPORT_REJECTED("rule.import_rejected", "import rejected: invalid document(s), nothing was imported"),

    // ---- investigations and traces --------------------------------------------------------------------------------
    INVESTIGATION_NOT_FOUND("investigation.not_found", "no such investigation"),
    INVESTIGATION_NOT_FINISHED("investigation.not_finished", "the investigation has not finished"),
    TRACE_NOT_FOUND("trace.not_found", "no such trace"),
    TRACE_DIRECTION_INVALID("trace.direction_invalid", "direction must be out or in"),
    TRACE_METHOD_INVALID("trace.method_invalid", "method must be PROPORTIONAL or FIFO"),
    REVIEW_DECISION_REQUIRED("review.decision_required", "decision is required"),
    REVIEW_DECISION_INVALID("review.decision_invalid", "decision must be ACCEPTED, REJECTED or NEEDS_EVIDENCE"),
    SUMMARY_PROVIDER_REQUIRED("summary.provider_required", "provider is required"),

    // ---- watchlist, webhooks, administration ----------------------------------------------------------------------
    WATCHLIST_FIELDS_REQUIRED("watchlist.fields_required", "'network' and 'address' are required"),
    WATCHLIST_NAME_TOO_LONG("watchlist.name_too_long", "'name' is limited to {max} characters"),
    WATCHLIST_DUPLICATE("watchlist.duplicate", "this wallet is already on the watchlist"),
    WATCHLIST_NOT_FOUND("watchlist.not_found", "no watchlist entry '{id}'"),
    WEBHOOK_NOT_FOUND("webhook.not_found", "webhook {id} does not exist"),
    CONNECTOR_INVALID("connector.invalid", "connector is invalid"),
    KEY_NOT_FOUND("key.not_found", "API key {id} does not exist"),
    KEY_REQUEST_INVALID("key.request_invalid", "API key request is invalid"),
    USER_NOT_FOUND("user.not_found", "user {id} does not exist"),
    USER_UNKNOWN_ROLE("user.unknown_role", "unknown role"),
    BACKFILL_UNKNOWN_NETWORK("backfill.unknown_network", "unknown network"),
    BACKFILL_NOT_CONFIGURED_FOR("backfill.not_configured_for",
            "no freeze backfill configured for {network} (needs a real provider and oci.chains.{network}.freeze-backfill.from-block)"),
    BACKFILL_NONE_CONFIGURED("backfill.none_configured", "no freeze backfill is configured (oci.chains.<network>.freeze-backfill.from-block)"),

    // ---- field errors (errors[].code) -----------------------------------------------------------------------------
    FIELD_REQUIRED("field.required", "{field} is required"),
    FIELD_TOO_LONG("field.too_long", "{field} is too long (max {max} characters)"),
    FIELD_MIN_LENGTH("field.min_length", "{field} needs at least {min} characters"),
    FIELD_INVALID_HTTP_URL("field.invalid_http_url", "must be an http(s) URL"),
    FIELD_INVALID_URL("field.invalid_url", "must be an https URL without credentials"),
    FIELD_MALFORMED_URL("field.malformed_url", "malformed URL"),
    FIELD_UNSUPPORTED_TYPE("field.unsupported_type", "supported types: {types}"),
    FIELD_EMAIL_UNAVAILABLE("field.email_unavailable", "EMAIL connectors are not available: no mail transport is configured in this version"),
    FIELD_NOT_CONFIGURED("field.not_configured", "TELEGRAM requires OCI_TELEGRAM_BOT_TOKEN on the server"),
    FIELD_UNKNOWN_EVENT("field.unknown_event", "unknown event {event}"),
    FIELD_INVALID_TARGET("field.invalid_target", "{reason}"),
    FIELD_UNKNOWN_PERMISSION("field.unknown_permission", "{reason}"),
    FIELD_EXCEEDS_CREATOR("field.exceeds_creator", "you do not hold {permission}"),
    FIELD_IN_PAST("field.in_past", "expiry must be in the future"),
    FIELD_MAX_EXCEEDED("field.max_exceeded", "expiry must be <= {days} days"),
    FIELD_UNSUPPORTED_NETWORK("field.unsupported_network", "network not enabled"),
    FIELD_UNKNOWN_NETWORK("field.unknown_network", "unknown network '{network}'"),
    FIELD_INVALID_ROLE("field.invalid_role", "role must be one of {roles}");

    /** {@code {name}} placeholders; braces that do not hold an identifier are literal text. */
    static final Pattern PARAM = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)}");

    private final String id;
    private final String template;

    ApiCode(String id, String template) {
        this.id = id;
        this.template = template;
    }

    public String id() { return id; }
    public String template() { return template; }

    /** The placeholder names of the template, in order of first appearance. */
    public Set<String> params() {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = PARAM.matcher(template);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** The template with its params filled in; the supplied params must be exactly the declared ones. */
    public String fill(Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        if (!p.keySet().equals(params())) {
            throw new IllegalArgumentException(id + " declares params " + params() + " but got " + p.keySet());
        }
        return PARAM.matcher(template).replaceAll(m -> Matcher.quoteReplacement(String.valueOf(p.get(m.group(1)))));
    }

    /**
     * The catalogue published as {@code docs/api/codes.json}: every code of this enum plus {@code extra}
     * ({@code code -> template}, e.g. the rule validation keys), sorted by code.
     */
    public static String catalogJson(Map<String, String> extra) {
        TreeMap<String, String> all = new TreeMap<>(extra);
        for (ApiCode c : values()) all.put(c.id, c.template);
        StringBuilder sb = new StringBuilder("[\n");
        int i = 0;
        for (Map.Entry<String, String> e : all.entrySet()) {
            sb.append("  {\n    \"code\": ").append(quote(e.getKey())).append(",\n    \"template\": ").append(quote(e.getValue()))
                    .append("\n  }").append(++i < all.size() ? ",\n" : "\n");
        }
        return sb.append("]\n").toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
            }
        }
        return sb.append('"').toString();
    }
}
