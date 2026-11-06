package io.openchain.alerting.rule;

import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.alerting.rule.compile.RuleCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Facade: parse (safe) -> validate (strict) -> compile (immutable). The entry point other modules should use. */
public final class RuleLoader {

    /** Validation outcome with the compiled rule when valid. */
    public record LoadResult(List<ValidationError> errors, CompiledRule rule) {
        public LoadResult { errors = List.copyOf(errors); }
        public boolean valid() { return errors.isEmpty(); }
    }

    private final RuleParser parser;
    private final RuleValidator validator;
    private final RuleCompiler compiler;
    private final FieldCatalog catalog;
    private final RuleLimits limits;

    public RuleLoader(FieldCatalog catalog, RuleLimits limits) {
        this.catalog = catalog;
        this.limits = limits;
        this.parser = new RuleParser(limits);
        this.validator = new RuleValidator(catalog, limits);
        this.compiler = new RuleCompiler(catalog, limits);
    }

    public RuleLoader() { this(FieldCatalog.defaults(), RuleLimits.defaults()); }

    /** Never throws for bad input: every problem is reported as a {@link ValidationError}. */
    public LoadResult tryLoad(String source, Format format) {
        try {
            ValidationResult vr = validator.validate(parser.parse(source, format));
            if (!vr.valid()) return new LoadResult(vr.errors(), null);
            return new LoadResult(List.of(), compiler.compile(vr.rule()));
        } catch (RuleParser.RuleParseException e) {
            return new LoadResult(List.of(e.error()), null);
        }
    }

    /** @throws RuleValidationException with all errors */
    public CompiledRule load(String source, Format format) {
        LoadResult r = tryLoad(source, format);
        if (!r.valid()) throw new RuleValidationException(r.errors());
        return r.rule();
    }

    public CompiledRule load(Path file) {
        try {
            return load(Files.readString(file), Format.fromFileName(file.getFileName().toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public FieldCatalog catalog() { return catalog; }
    public RuleLimits limits() { return limits; }
}
