package io.openchain.app.api;

import io.openchain.alerting.rule.FieldCatalog;
import io.openchain.alerting.rule.Rule;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.alerting.testing.DryRunOptions;
import io.openchain.alerting.testing.DryRunReport;
import io.openchain.alerting.testing.EmbeddedTestReport;
import io.openchain.alerting.testing.RuleTester;
import io.openchain.app.chain.EventHistory;
import io.openchain.app.config.OciProperties;
import io.openchain.app.rules.RuleRegistry;
import io.openchain.app.security.ApiKeyService;
import org.springframework.security.core.Authentication;
import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Network;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rules: CRUD-ish versioned store, validation, DRY_RUN tests and YAML import/export.
 * Bodies: {@code {"source": "<yaml>"}} (doc) or {@code {"yaml": "<yaml>"}} (SPA), a JSON rule document, or a raw
 * YAML body with {@code Content-Type: application/yaml}.
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private static final Pattern DOC_SEPARATOR = Pattern.compile("(?m)^---[ \\t]*$");
    private static final MediaType YAML = MediaType.parseMediaType("application/yaml");
    private static final List<String> SEVERITIES = List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    /** Closed value sets of STRING catalog fields (served as SPA type "enum"). */
    private static final Map<String, List<String>> ENUMS = Map.of(
            "network", java.util.Arrays.stream(Network.values()).map(Network::id).toList(),
            "event.type", java.util.Arrays.stream(ChainEvent.EventType.values()).map(Enum::name).toList(),
            "wallet.risk_level", java.util.Arrays.stream(io.openchain.core.risk.RiskLevel.values()).map(Enum::name).toList(),
            "signal.type", java.util.Arrays.stream(io.openchain.core.detection.SignalType.values()).map(Enum::name).toList(),
            "signal.severity", SEVERITIES,
            "alert.severity", SEVERITIES,
            "signal.evidence_level", List.of("ANOMALY", "RISK_SIGNAL", "CONFIRMED_BY_SOURCE"),
            "freeze.action", List.of("FREEZE", "UNFREEZE"));

    private final RuleRegistry registry;
    private final RuleTester tester;
    private final FieldCatalog catalog;
    private final EventHistory history;
    private final OciProperties props;
    private final JsonMapper json;
    private final Clock clock;

    private final io.openchain.alerting.alert.AlertService alertService;

    /**
     * Noise report: alerts per rule (total, open, false positives, resolved, created in the last {@code hours}) and the
     * false-positive rate among closed alerts. The measurement behind rule tuning (roadmap phase 0, task 0.10).
     */
    @GetMapping("/noise")
    public List<Map<String, Object>> noise(@RequestParam(defaultValue = "24") int hours) {
        if (hours < 1 || hours > 24 * 90) throw ApiException.badRequest("hours must be between 1 and 2160");
        java.time.Instant since = clock.instant().minus(java.time.Duration.ofHours(hours));
        return alertService.repository().statsByRule(since).stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("ruleId", s.ruleId());
            m.put("total", s.total());
            m.put("open", s.open());
            m.put("falsePositive", s.falsePositive());
            m.put("resolved", s.resolved());
            m.put("recent", s.recent());
            m.put("windowHours", hours);
            m.put("falsePositiveRate", s.falsePositiveRate());
            return m;
        }).toList();
    }

    public RuleController(RuleRegistry registry, RuleTester tester, FieldCatalog catalog, EventHistory history, OciProperties props,
                          JsonMapper json, Clock clock, io.openchain.alerting.alert.AlertService alertService) {
        this.alertService = alertService;
        this.registry = registry;
        this.tester = tester;
        this.catalog = catalog;
        this.history = history;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /** A rule document extracted from a request body. */
    record Source(String text, Format format, JsonNode envelope) {}

    // ---- read ---------------------------------------------------------------------------------------------------

    @GetMapping
    public CursorPage<Dto.RuleDto> list(@RequestParam(required = false) Boolean enabled, @RequestParam(required = false) String tag,
                                        @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer limit) {
        var list = registry.list().stream()
                .filter(r -> enabled == null || r.enabled() == enabled)
                .filter(r -> tag == null || tag.isBlank() || r.current().rule().tags().contains(tag))
                .map(RuleController::dto).toList();
        return CursorPage.of(list, cursor, limit);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Dto.RuleDto> get(@PathVariable String id) {
        RuleRegistry.StoredRule r = registry.get(id);
        return ResponseEntity.ok().eTag("\"" + r.id() + "-v" + r.current().number() + "\"").body(dto(r));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<String> export(@PathVariable String id, @RequestParam(required = false) Integer version) {
        RuleRegistry.StoredRule r = registry.get(id);
        RuleRegistry.Version v = version == null ? r.current()
                : r.version(version).orElseThrow(() -> ApiException.notFound("rule " + id + " has no version " + version));
        MediaType type = v.format() == Format.JSON ? MediaType.APPLICATION_JSON : YAML;
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + (v.format() == Format.JSON ? ".json" : ".yaml") + "\"")
                .body(v.source());
    }

    /** All current versions as one multi-document YAML stream (JSON sources are YAML-compatible). */
    @GetMapping("/export")
    public ResponseEntity<String> exportAll() {
        StringBuilder sb = new StringBuilder();
        for (RuleRegistry.StoredRule r : registry.list()) {
            if (!sb.isEmpty()) sb.append("---\n");
            String src = r.current().source();
            sb.append(src.endsWith("\n") ? src : src + "\n");
        }
        return ResponseEntity.ok().contentType(YAML).body(sb.toString());
    }

    @GetMapping("/fields")
    public List<Dto.FieldDefDto> fields() {
        return catalog.all().stream().map(f -> new Dto.FieldDefDto(f.name(), ENUMS.containsKey(f.name()) ? "enum" : switch (f.type()) {
            case DECIMAL -> "amount";
            case INTEGER -> "number";
            case BOOLEAN -> "boolean";
            case TIMESTAMP -> "timestamp";
            case STRING -> f.name().endsWith(".from") || f.name().endsWith(".to") || f.name().endsWith(".address")
                    || f.name().endsWith(".contract") || f.name().endsWith(".spender") || f.name().endsWith(".owner")
                    || f.name().endsWith(".new_owner") ? "address" : "string";
        }, f.description(), f.sourceBacked(), ENUMS.get(f.name()), PopulatedFields.FIELDS.contains(f.name()))).toList();
    }

    /** Event types and whether the current pipeline produces them. */
    @GetMapping("/event-types")
    public List<Map<String, Object>> eventTypes() {
        return java.util.Arrays.stream(ChainEvent.EventType.values())
                .map(t -> Map.<String, Object>of("type", t.name(), "available", PopulatedFields.EVENT_TYPES.contains(t))).toList();
    }

    // ---- validate -----------------------------------------------------------------------------------------------

    /** 200 {@code {valid: true, compiledHash}} or 422 problem with {@code errors[]} JSON pointers. */
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody(required = false) String body,
                                        @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        Source s = source(body, contentType);
        RuleLoader.LoadResult r = registry.validate(s.text(), s.format());
        if (!r.valid()) throw ApiException.validation("Rule source is invalid", RuleErrors.of(r.errors(), ""));
        CompiledRule c = r.rule();
        EmbeddedTestReport embedded = tester.runEmbeddedTests(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", true);
        out.put("errors", List.of());
        out.put("ruleId", c.id());
        out.put("ruleVersion", c.version());
        out.put("kind", c.kind().name());
        out.put("compiledHash", "sha256:" + sha256(s.text()));
        out.put("embeddedTests", embedded.results().stream().map(t -> Map.of("kind", t.kind().name(), "name", t.name(),
                "passed", t.passed(), "message", t.message())).toList());
        return out;
    }

    // ---- write --------------------------------------------------------------------------------------------------

    @PostMapping
    public ResponseEntity<Dto.RuleDto> create(@RequestBody(required = false) String body,
                                              @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                              Authentication auth) {
        Source s = source(body, contentType);
        RuleRegistry.StoredRule r = registry.create(s.text(), s.format());
        ApiKeyService.AUDIT.info("rule.create id={} version={} actor={}", r.id(), r.current().number(), actor(auth));
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.LOCATION, "/api/v1/rules/" + r.id()).body(dto(r));
    }

    /** New immutable version. TODO(concurrency): require If-Match (412 on mismatch). */
    @PutMapping("/{id}")
    public Dto.RuleDto update(@PathVariable String id, @RequestBody(required = false) String body,
                              @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                              Authentication auth) {
        registry.get(id);
        Source s = source(body, contentType);
        RuleRegistry.StoredRule r = registry.update(id, s.text(), s.format());
        ApiKeyService.AUDIT.info("rule.update id={} version={} actor={}", id, r.current().number(), actor(auth));
        return dto(r);
    }

    private static String actor(Authentication auth) { return auth == null ? null : auth.getName(); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id, Authentication auth) {
        registry.delete(id);
        ApiKeyService.AUDIT.info("rule.delete id={} actor={}", id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/enable")
    public Dto.RuleDto enable(@PathVariable String id, Authentication auth) {
        RuleRegistry.StoredRule r = registry.setEnabled(id, true);
        ApiKeyService.AUDIT.info("rule.enable id={} version={} actor={}", id, r.current().number(), auth.getName());
        return dto(r);
    }

    @PostMapping("/{id}/disable")
    public Dto.RuleDto disable(@PathVariable String id, Authentication auth) {
        RuleRegistry.StoredRule r = registry.setEnabled(id, false);
        ApiKeyService.AUDIT.info("rule.disable id={} actor={}", id, auth.getName());
        return dto(r);
    }

    /** One or many YAML documents ({@code ---} separated). All-or-nothing: any invalid document rejects the import. */
    @PostMapping("/import")
    public List<Dto.RuleDto> importRules(@RequestBody(required = false) String body,
                                         @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                                         Authentication auth) {
        String text = body;
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) text = source(body, contentType).text();
        if (text == null || text.isBlank()) throw ApiException.badRequest("empty import");
        List<String> docs = new ArrayList<>();
        for (String d : DOC_SEPARATOR.split(text)) if (!d.isBlank()) docs.add(d.strip() + "\n");
        if (docs.size() > 200) throw ApiException.badRequest("at most 200 rules per import");
        List<ApiException.FieldError> errors = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            RuleLoader.LoadResult r = registry.validate(docs.get(i), Format.YAML);
            if (!r.valid()) errors.addAll(RuleErrors.of(r.errors(), "/documents/" + i));
        }
        if (!errors.isEmpty()) throw ApiException.validation("import rejected: invalid document(s), nothing was imported", errors);
        List<Dto.RuleDto> out = new ArrayList<>();
        for (String d : docs) {
            RuleRegistry.StoredRule r = registry.upsert(d, Format.YAML);
            ApiKeyService.AUDIT.info("rule.import id={} version={} actor={}", r.id(), r.current().number(), actor(auth));
            out.add(dto(r));
        }
        return out;
    }

    // ---- dry run ------------------------------------------------------------------------------------------------

    public record TestRequest(String from, String to, String network, String source, String yaml, String mode) {}

    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable String id, @RequestBody(required = false) TestRequest req) {
        RuleRegistry.StoredRule r = registry.get(id);
        String src = req == null ? null : req.source() != null ? req.source() : req.yaml();
        CompiledRule c = src == null || src.isBlank() ? r.current().compiled() : registry.compile(src, Format.YAML);
        return dryRun(c, req);
    }

    /** Test an unsaved rule (SPA editor). */
    @PostMapping("/test")
    public Map<String, Object> testUnsaved(@RequestBody(required = false) TestRequest req) {
        String src = req == null ? null : req.source() != null ? req.source() : req.yaml();
        if (src == null || src.isBlank()) throw ApiException.badRequest("'source' (or 'yaml') is required");
        return dryRun(registry.compile(src, Format.YAML), req);
    }

    public record SimulateRequest(String source, String from, String to, String network) {}

    /**
     * The impact of a change of this rule (phase 4, ADR-0023 amendment 2): the stored version and the proposed source run
     * side by side on the same events held in memory, both as dry runs (isolated engine, no alert, no notification); the
     * stored rule is not changed. Level SIMULATED. The window is what the in-memory history holds, which may be short.
     */
    @PostMapping("/{id}/simulate")
    public Map<String, Object> simulate(@PathVariable String id, @RequestBody(required = false) SimulateRequest req) {
        RuleRegistry.StoredRule stored = registry.get(id);
        if (req == null || req.source() == null || req.source().isBlank()) throw ApiException.badRequest("'source' (the proposed rule) is required");
        CompiledRule current = stored.current().compiled();
        CompiledRule proposed = registry.compile(req.source(), Format.YAML);
        if (!proposed.id().equals(current.id())) throw ApiException.badRequest("the proposed source must keep the rule id '" + current.id() + "'");
        Instant to = InvestigationController.instant(req.to(), "to");
        Instant from = InvestigationController.instant(req.from(), "from");
        if (to == null) to = clock.instant();
        if (from != null && from.isAfter(to)) throw ApiException.badRequest("'from' must be before 'to'");
        Network n = req.network() == null || req.network().isBlank() ? null : InvestigationController.parseNetwork(req.network());
        List<ChainEvent> events = history.range(from, to, n, props.getRules().getMaxDryRunEvents());

        DryRunReport before = tester.dryRun(registry.executable(current), events, DryRunOptions.defaults());
        DryRunReport after = tester.dryRun(registry.executable(proposed), events, DryRunOptions.defaults());
        java.util.Set<String> b = new java.util.LinkedHashSet<>(), a = new java.util.LinkedHashSet<>();
        for (ChainEvent e : events) {
            if (e == null || e.removed()) continue;
            if (current.inScope(e) && current.matchesFilter(e, io.openchain.alerting.rule.compile.CompiledCondition.EvalStats.NOOP)) b.add(e.eventId());
            if (proposed.inScope(e) && proposed.matchesFilter(e, io.openchain.alerting.rule.compile.CompiledCondition.EvalStats.NOOP)) a.add(e.eventId());
        }
        List<String> onlyBefore = b.stream().filter(x -> !a.contains(x)).toList(), onlyAfter = a.stream().filter(x -> !b.contains(x)).toList();

        Map<String, Object> window = new LinkedHashMap<>();
        window.put("from", events.isEmpty() ? null : events.getFirst().eventTime().toString());
        window.put("to", events.isEmpty() ? null : events.getLast().eventTime().toString());
        window.put("note", "Events held in memory only (at most " + props.getRules().getMaxDryRunEvents() + "; the persisted journal of decision D5 "
                + "does not exist yet): the window may be short and is not a forecast.");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", "SIMULATED");
        out.put("ruleId", current.id());
        out.put("currentVersion", current.version());
        out.put("proposedVersion", proposed.version());
        out.put("evaluatedEvents", events.size());
        out.put("window", window);
        out.put("before", side(before));
        out.put("after", side(after));
        out.put("onlyBefore", onlyBefore.size());
        out.put("onlyAfter", onlyAfter.size());
        out.put("onlyBeforeSample", onlyBefore.stream().limit(20).toList());
        out.put("onlyAfterSample", onlyAfter.stream().limit(20).toList());
        return out;
    }

    private static Map<String, Object> side(DryRunReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("eventsInScope", r.eventsInScope());
        m.put("conditionMatches", r.conditionMatches());
        m.put("triggers", r.triggers());
        m.put("estimatedAlerts", r.estimatedAlerts());
        m.put("estimatedAlertsPerDay", r.estimatedAlertsPerDay());
        m.put("warnings", r.warnings().stream().map(DryRunReport.Warning::message).toList());
        return m;
    }

    private Map<String, Object> dryRun(CompiledRule rule, TestRequest req) {
        if (req != null && req.mode() != null && !req.mode().equalsIgnoreCase("DRY_RUN"))
            throw ApiException.badRequest("only mode DRY_RUN is supported");
        Instant to = req == null ? null : InvestigationController.instant(req.to(), "to");
        Instant from = req == null ? null : InvestigationController.instant(req.from(), "from");
        if (to == null) to = clock.instant();
        if (from == null) from = to.minus(Duration.ofDays(1));
        if (from.isAfter(to)) throw ApiException.badRequest("'from' must be before 'to'");
        Network n = req == null || req.network() == null || req.network().isBlank() ? null : InvestigationController.parseNetwork(req.network());
        List<ChainEvent> events = history.range(from, to, n, props.getRules().getMaxDryRunEvents());
        DryRunReport rep = tester.dryRun(registry.executable(rule), events, DryRunOptions.defaults());
        List<Map<String, Object>> matches = new ArrayList<>();
        for (String eventId : rep.matchedEventIdsSample()) {
            Optional<ChainEvent> e = history.find(eventId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventId", eventId);
            m.put("network", e.map(x -> x.network().id()).orElse(null));
            m.put("eventTime", e.map(x -> x.eventTime().toString()).orElse(null));
            m.put("summary", e.map(RuleController::summary).orElse(eventId));
            m.put("fields", e.map(x -> ApiMapper.jsonSafe(x.fields())).orElse(Map.of()));
            matches.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "DRY_RUN");
        out.put("ruleRef", rep.ruleRef());
        out.put("from", from.toString());
        out.put("to", to.toString());
        out.put("evaluatedEvents", rep.totalEvents());
        out.put("eventsInScope", rep.eventsInScope());
        out.put("conditionMatches", rep.conditionMatches());
        out.put("matchCount", rep.triggers());
        out.put("estimatedAlerts", rep.estimatedAlerts());
        out.put("estimatedTriggersPerDay", rep.estimatedTriggersPerDay());
        out.put("estimatedAlertsPerDay", rep.estimatedAlertsPerDay());
        out.put("observedSpanSeconds", rep.observedSpan().toSeconds());
        out.put("permissive", rep.permissive());
        out.put("warnings", rep.warnings().stream().map(DryRunReport.Warning::message).toList());
        out.put("warningCodes", rep.warnings().stream().map(w -> Map.of("code", w.code(), "message", w.message())).toList());
        out.put("matches", matches);
        return out;
    }

    private static String summary(ChainEvent e) {
        Map<String, Object> f = e.fields();
        if (f.get("transaction.amount") != null) {
            return ApiMapper.jsonValue(f.get("transaction.amount")) + " " + f.getOrDefault("transaction.token.symbol", "") + " "
                    + f.get("transaction.from") + " -> " + f.get("transaction.to");
        }
        if (f.get("signal.type") != null) return "signal " + f.get("signal.type") + " on " + f.get("wallet.address");
        return e.type() + " " + e.eventId();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private Source source(String body, String contentType) {
        if (body == null || body.isBlank()) throw ApiException.badRequest("request body is required");
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("yaml") || ct.startsWith("text/plain")) return new Source(body, Format.YAML, null);
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("malformed JSON body");
        }
        if (root == null || !root.isObject()) throw ApiException.badRequest("JSON body must be an object");
        for (String k : List.of("source", "yaml")) {
            JsonNode v = root.get(k);
            if (v != null && v.isString()) {
                JsonNode f = root.get("format");
                Format fmt = f != null && f.isString() && f.asString().equalsIgnoreCase("json") ? Format.JSON : Format.YAML;
                return new Source(v.asString(), fmt, root);
            }
        }
        if (root.has("apiVersion")) return new Source(body, Format.JSON, root);
        throw ApiException.badRequest("expected {\"source\": \"<yaml>\"}, {\"yaml\": \"<yaml>\"} or a JSON rule document");
    }

    static Dto.RuleDto dto(RuleRegistry.StoredRule r) {
        RuleRegistry.Version v = r.current();
        Rule rule = v.rule();
        return new Dto.RuleDto(r.id(), rule.name(), rule.description(), r.enabled(), rule.severity().name(),
                rule.evidenceLevel() == null ? null : rule.evidenceLevel().name(), v.source(), v.format().name(), v.number(),
                rule.version(), v.compiledHash(), rule.tags(),
                rule.scope().networks().stream().map(Network::id).sorted().toList(), r.origin(), r.createdAt(), r.updatedAt(),
                RuleRegistry.unreachable(rule));
    }

    private static String sha256(String s) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
