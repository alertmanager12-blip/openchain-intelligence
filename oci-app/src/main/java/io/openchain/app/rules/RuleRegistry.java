package io.openchain.app.rules;

import io.openchain.alerting.engine.RuleEngine;
import io.openchain.alerting.rule.Rule;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.ValidationError;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.alerting.rule.compile.RuleCompiler;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rule store: immutable versions per rule id, enabled flag, and registration into the live {@link RuleEngine}.
 * The engine only evaluates rules whose compiled form is enabled, so an enabled rule whose source says
 * {@code enabled: false} is recompiled from a copy with {@code enabled = true} (the source text is never rewritten).
 * TODO(persistence): rule_versions table; PUT with If-Match / ETag; tenant scoping.
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
    private final Map<String, StoredRule> rules = new LinkedHashMap<>();

    public RuleRegistry(RuleLoader loader, RuleCompiler compiler, RuleEngine engine, Clock clock) {
        this.loader = loader;
        this.compiler = compiler;
        this.engine = engine;
        this.clock = clock;
    }

    public RuleLoader.LoadResult validate(String source, Format format) { return loader.tryLoad(source, format); }

    /** Compiles or throws {@link InvalidRuleException}. */
    public CompiledRule compile(String source, Format format) {
        RuleLoader.LoadResult r = loader.tryLoad(source, format);
        if (!r.valid()) throw new InvalidRuleException("rule source is invalid (" + r.errors().size() + " error(s))", r.errors());
        return r.rule();
    }

    /** Loads every *.yaml/*.yml/*.json file under {@code dir}; invalid files are logged and skipped. Returns the count loaded. */
    public int loadDirectory(Path dir) {
        int loaded = 0;
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
                    if (rules.containsKey(id)) {
                        log.warn("rule file {} ignored: id '{}' already loaded", p, id);
                        continue;
                    }
                    Instant now = clock.instant();
                    StoredRule sr = new StoredRule(id, List.of(version(1, src, fmt, r.rule(), now)), r.rule().rule().enabled(),
                            "repository", now, now);
                    rules.put(id, sr);
                    syncEngine(sr);
                    loaded++;
                }
            } catch (IOException | RuntimeException e) {
                log.warn("rule file {} rejected: {}", p, e.getMessage());
            }
        }
        log.info("loaded {} rule(s) from {}", loaded, dir.toAbsolutePath().normalize());
        return loaded;
    }

    /** Creates a new rule, always disabled. */
    public synchronized StoredRule create(String source, Format format) {
        CompiledRule c = compile(source, format);
        if (rules.containsKey(c.id())) throw new RuleConflictException("a rule with id '" + c.id() + "' already exists");
        Instant now = clock.instant();
        StoredRule sr = new StoredRule(c.id(), List.of(version(1, source, format, c, now)), false, "api", now, now);
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
        versions.add(version(old.current().number() + 1, source, format, c, now));
        StoredRule sr = new StoredRule(id, versions, old.enabled(), old.origin(), old.createdAt(), now);
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
        rules.put(id, sr);
        syncEngine(sr);
        return sr;
    }

    public synchronized void delete(String id) {
        get(id);
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
