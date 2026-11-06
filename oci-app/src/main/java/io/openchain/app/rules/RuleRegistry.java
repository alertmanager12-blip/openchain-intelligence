package io.openchain.app.rules;

import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.rule.Rule;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.ValidationError;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.alerting.rule.compile.RuleCompiler;
import io.openchain.app.persistence.OptimisticLockException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rule store: immutable versions per rule id, enabled flag, and registration into the live {@link RuleEngine}.
 * The engine only evaluates rules whose compiled form is enabled, so an enabled rule whose source says
 * {@code enabled: false} is recompiled from a copy with {@code enabled = true} (the source text is never rewritten).
 * Persisted through {@link RuleRepository} (in memory, or the {@code rules}/{@code rule_versions} tables with
 * optimistic locking); this class keeps the compiled forms in memory (single instance: another instance's changes
 * are seen after a restart). TODO: PUT with If-Match / ETag; tenant scoping; multi-instance cache invalidation.
 */
@Service
public class RuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuleRegistry.class);

    public record Version(int number, String source, Format format, CompiledRule compiled, String compiledHash, Instant createdAt) {
        public Rule rule() { return compiled.rule(); }
    }

    /** Snapshot of one stored rule. {@code origin} is {@code repository} (loaded from files) or {@code api}. */
    public record StoredRule(String id, List<Version> versions, boolean enabled, String origin, Instant createdAt, Instant updatedAt) {
        public StoredRule { versions = List.copyOf(versions); }
        public Version current() { return versions.getLast(); }
        public Optional<Version> version(int n) { return versions.stream().filter(v -> v.number() == n).findFirst(); }
    }

    /** The source is invalid. */
    public static final class InvalidRuleException extends RuntimeException {
        private final List<ValidationError> errors;
        public InvalidRuleException(String message, List<ValidationError> errors) {
            super(message);
            this.errors = List.copyOf(errors);
        }
        public List<ValidationError> errors() { return errors; }
    }

    public static final class RuleConflictException extends RuntimeException {
        public RuleConflictException(String message) { super(message); }
    }

    public static final class UnknownRuleException extends RuntimeException {
        public UnknownRuleException(String id) { super("rule '" + id + "' does not exist"); }
    }

    private final RuleLoader loader;
    private final RuleCompiler compiler;
    private final RuleEngine engine;
    private final Clock clock;
    private final RuleRepository repository;
    /** Live (not deleted) rules with their compiled versions. */
    private final Map<String, StoredRule> rules = new LinkedHashMap<>();
    /** Persisted rows, tombstones included (row version for optimistic locking). */
    private final Map<String, RuleRepository.RuleRow> rows = new HashMap<>();

    public RuleRegistry(RuleLoader loader, RuleCompiler compiler, RuleEngine engine, Clock clock, RuleRepository repository) {
        this.loader = loader;
        this.compiler = compiler;
        this.engine = engine;
        this.clock = clock;
        this.repository = repository;
        loadPersisted();
    }

    /** Restores persisted rules (compiled again from their stored source) and registers the enabled ones. */
    private synchronized void loadPersisted() {
        int live = 0;
        for (RuleRepository.RuleRow row : repository.findAll()) {
            rows.put(row.id(), row);
            if (row.deleted()) continue;
            try {
                StoredRule sr = materialize(row);
                rules.put(sr.id(), sr);
                syncEngine(sr);
                live++;
            } catch (RuntimeException e) {
                log.error("persisted rule '{}' v{} cannot be compiled and is NOT active: {}", row.id(), row.current().number(), e.getMessage());
            }
        }
        if (!rows.isEmpty()) log.info("restored {} persisted rule(s) ({} enabled)", live, rules.values().stream().filter(StoredRule::enabled).count());
    }

    /** Compiles the stored versions; older versions that no longer compile are left out (the current one must compile). */
    private StoredRule materialize(RuleRepository.RuleRow row) {
        List<Version> versions = new ArrayList<>();
        for (RuleRepository.VersionRow v : row.versions()) {
            boolean current = v.number() == row.current().number();
            RuleLoader.LoadResult r = loader.tryLoad(v.source(), v.format());
            if (!r.valid()) {
                if (current) throw new InvalidRuleException("stored source is invalid: " + r.errors(), r.errors());
                log.warn("persisted rule '{}' v{} no longer compiles; omitted from the version list", row.id(), v.number());
                continue;
            }
            versions.add(new Version(v.number(), v.source(), v.format(), r.rule(), v.compiledHash(), v.createdAt()));
        }
        return new StoredRule(row.id(), versions, row.enabled(), row.origin(), row.createdAt(), row.updatedAt());
    }

    /** Writes the rule (before any in-memory change); a concurrent modification becomes a {@link RuleConflictException}. */
    private void persist(StoredRule sr, Instant deletedAt) {
        RuleRepository.RuleRow prev = rows.get(sr.id());
        List<RuleRepository.VersionRow> vs = sr.versions().stream().map(v -> new RuleRepository.VersionRow(v.number(), v.source(),
                v.format(), v.compiledHash(), v.rule().version(), v.createdAt())).toList();
        try {
            rows.put(sr.id(), repository.save(new RuleRepository.RuleRow(sr.id(), sr.origin(), sr.enabled(), vs, sr.createdAt(),
                    sr.updatedAt(), deletedAt, prev == null ? 0 : prev.rowVersion())));
        } catch (OptimisticLockException e) {
            throw new RuleConflictException(e.getMessage());
        }
    }

    public RuleLoader.LoadResult validate(String source, Format format) { return loader.tryLoad(source, format); }

    /** Compiles or throws {@link InvalidRuleException}. */
    public CompiledRule compile(String source, Format format) {
        RuleLoader.LoadResult r = loader.tryLoad(source, format);
        if (!r.valid()) throw new InvalidRuleException("rule source is invalid (" + r.errors().size() + " error(s))", r.errors());
        return r.rule();
    }

    /**
     * Loads every *.yaml/*.yml/*.json file under {@code dir}; invalid files are logged and skipped. Idempotent with a
     * persistent store: a file whose exact source is already a stored version of its rule is left alone, a changed
     * file adds a new version (enabled flag kept), a rule deleted by an operator is not re-imported, and an id
     * created through the API is never overwritten by a file. Returns the number of files accepted.
     */
    public int loadDirectory(Path dir) {
        int created = 0, updated = 0, unchanged = 0;
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".yaml") || n.endsWith(".yml") || n.endsWith(".json");
            }).sorted().toList();
        } catch (IOException e) {
            log.warn("cannot read rules directory {}: {}", dir, e.getMessage());
            return 0;
        }
        Set<String> seen = new HashSet<>();
        for (Path p : files) {
            try {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Format fmt = Format.fromFileName(p.getFileName().toString());
                RuleLoader.LoadResult r = loader.tryLoad(src, fmt);
                if (!r.valid()) {
                    log.warn("rule file {} rejected: {}", p, r.errors());
                    continue;
                }
                synchronized (this) {
                    String id = r.rule().id();
                    if (!seen.add(id)) {
                        log.warn("rule file {} ignored: id '{}' already loaded", p, id);
                        continue;
                    }
                    RuleRepository.RuleRow row = rows.get(id);
                    StoredRule old = rules.get(id);
                    String hash = "sha256:" + sha256(src);
                    Instant now = clock.instant();
                    if (row == null) {
                        StoredRule sr = new StoredRule(id, List.of(version(1, src, fmt, r.rule(), now)), r.rule().rule().enabled(),
                                "repository", now, now);
                        persist(sr, null);
                        rules.put(id, sr);
                        syncEngine(sr);
                        created++;
                    } else if (row.deleted()) {
                        log.info("rule file {} skipped: rule '{}' was deleted by an operator", p, id);
                    } else if (old == null || !"repository".equals(row.origin())) {
                        log.warn("rule file {} ignored: id '{}' already exists ({})", p, id, old == null ? "not compilable" : "created through the API");
                    } else if (row.versions().stream().anyMatch(v -> v.compiledHash().equals(hash))) {
                        unchanged++;
                    } else {
                        List<Version> versions = new ArrayList<>(old.versions());
                        versions.add(version(row.versions().getLast().number() + 1, src, fmt, r.rule(), now));
                        StoredRule sr = new StoredRule(id, versions, old.enabled(), old.origin(), old.createdAt(), now);
                        persist(sr, null);
                        rules.put(id, sr);
                        syncEngine(sr);
                        log.info("rule '{}' updated from {} (new version {})", id, p.getFileName(), sr.current().number());
                        updated++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.warn("rule file {} rejected: {}", p, e.getMessage());
            }
        }
        int loaded = created + updated + unchanged;
        log.info("loaded {} rule(s) from {} ({} new, {} updated, {} unchanged)", loaded, dir.toAbsolutePath().normalize(),
                created, updated, unchanged);
        return loaded;
    }

    /** Creates a new rule, always disabled. An id deleted earlier is revived with a new version. */
    public synchronized StoredRule create(String source, Format format) {
        CompiledRule c = compile(source, format);
        if (rules.containsKey(c.id())) throw new RuleConflictException("a rule with id '" + c.id() + "' already exists");
        Instant now = clock.instant();
        RuleRepository.RuleRow tomb = rows.get(c.id());
        StoredRule sr;
        if (tomb != null) {
            List<Version> versions = new ArrayList<>();
            try {
                versions.addAll(materialize(tomb).versions());
            } catch (RuntimeException e) {
                log.warn("history of deleted rule '{}' not restored: {}", c.id(), e.getMessage());
            }
            versions.add(version(tomb.versions().getLast().number() + 1, source, format, c, now));
            sr = new StoredRule(c.id(), versions, false, "api", tomb.createdAt(), now);
        } else {
            sr = new StoredRule(c.id(), List.of(version(1, source, format, c, now)), false, "api", now, now);
        }
        persist(sr, null);
        rules.put(c.id(), sr);
        syncEngine(sr);
        return sr;
    }

    /** Adds a new immutable version; the enabled flag is kept. */
    public synchronized StoredRule update(String id, String source, Format format) {
        StoredRule old = get(id);
        CompiledRule c = compile(source, format);
        if (!c.id().equals(id)) {
            throw new InvalidRuleException("rule id in the source does not match the path",
                    List.of(new ValidationError("$.id", ValidationError.Code.CONFLICT,
                            "id '" + c.id() + "' does not match '" + id + "'")));
        }
        Instant now = clock.instant();
        List<Version> versions = new ArrayList<>(old.versions());
        versions.add(version(rows.get(id).versions().getLast().number() + 1, source, format, c, now));
        StoredRule sr = new StoredRule(id, versions, old.enabled(), old.origin(), old.createdAt(), now);
        persist(sr, null);
        rules.put(id, sr);
        syncEngine(sr);
        return sr;
    }

    /** Create or new version (enabled flag kept for existing rules, disabled for new ones). */
    public synchronized StoredRule upsert(String source, Format format) {
        CompiledRule c = compile(source, format);
        return rules.containsKey(c.id()) ? update(c.id(), source, format) : create(source, format);
    }

    public synchronized StoredRule setEnabled(String id, boolean enabled) {
        StoredRule old = get(id);
        StoredRule sr = new StoredRule(id, old.versions(), enabled, old.origin(), old.createdAt(), clock.instant());
        persist(sr, null);
        rules.put(id, sr);
        syncEngine(sr);
        return sr;
    }

    /** Soft delete: versions are kept (audit), the rule disappears from the API and the engine. */
    public synchronized void delete(String id) {
        StoredRule old = get(id);
        Instant now = clock.instant();
        persist(new StoredRule(id, old.versions(), false, old.origin(), old.createdAt(), now), now);
        rules.remove(id);
        engine.unregister(id);
    }

    public synchronized Optional<StoredRule> find(String id) { return Optional.ofNullable(rules.get(id)); }

    public synchronized StoredRule get(String id) {
        StoredRule r = rules.get(id);
        if (r == null) throw new UnknownRuleException(id);
        return r;
    }

    public synchronized List<StoredRule> list() {
        return rules.values().stream().sorted(Comparator.comparing(StoredRule::id)).toList();
    }

    /** The compiled rule as the engine must evaluate it (enabled), regardless of the source's own flag. */
    public CompiledRule executable(CompiledRule c) {
        Rule r = c.rule();
        if (r.enabled()) return c;
        return compiler.compile(new Rule(r.apiVersion(), r.id(), r.version(), r.name(), r.description(), r.author(), true,
                r.scope(), r.conditions(), r.aggregation(), r.sequence(), r.severity(), r.evidenceLevel(), r.actions(),
                r.cooldown(), r.dedupKey(), r.documentation(), r.expectedConfidence(), r.tags(), r.examples(), r.tests()));
    }

    private void syncEngine(StoredRule sr) {
        if (sr.enabled()) engine.register(executable(sr.current().compiled()));
        else engine.unregister(sr.id());
    }

    private static Version version(int n, String source, Format format, CompiledRule c, Instant now) {
        return new Version(n, source, format, c, "sha256:" + sha256(source), now);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
