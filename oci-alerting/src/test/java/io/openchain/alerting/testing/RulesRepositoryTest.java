package io.openchain.alerting.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.rule.Rule;
import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser;
import io.openchain.alerting.rule.RuleParser.Format;
import io.openchain.alerting.rule.RuleTranslation;
import io.openchain.alerting.rule.RuleValidator;
import io.openchain.alerting.rule.ValidationResult;
import io.openchain.alerting.rule.compile.RuleCompiler;
import io.openchain.alerting.rule.compile.CompiledRule;
import io.openchain.core.model.Network;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Loads, validates and runs the embedded examples/tests of EVERY rule file under the repository's rules/ folder. */
class RulesRepositoryTest {

    static Path rulesDir() {
        String prop = System.getProperty("oci.rules.dir");
        Path p = prop != null ? Path.of(prop) : Path.of("../rules");
        assertThat(p).as("rules directory").isDirectory();
        return p.toAbsolutePath().normalize();
    }

    static List<Path> ruleFiles() throws IOException {
        try (Stream<Path> s = Files.walk(rulesDir())) {
            return s.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml") || f.toString().endsWith(".json"))
                    .sorted().toList();
        }
    }

    static final java.util.regex.Pattern ACCUSATORY = java.util.regex.Pattern.compile(
            "\\b(criminals?|scammers?|thie(f|ves)|fraudsters?|terrorists?|launderers?|hackers?|guilty|illegal)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** French counterpart of {@link #ACCUSATORY} (masculine, feminine and plural forms). */
    static final java.util.regex.Pattern ACCUSATORY_FR = java.util.regex.Pattern.compile(
            "\\b(criminel(le)?s?|escrocs?|voleu(r|se)s?|fraudeu(r|se)s?|coupables?|ill[ée]ga(l|le|les|ux)|terroristes?"
                    + "|blanchisseu(r|se)s?)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE
                    | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS);

    final RuleLoader loader = new RuleLoader();
    final RuleTester tester = new RuleTester(loader);

    @Test void repositoryContainsTheReferenceRules() throws IOException {
        List<String> rel = ruleFiles().stream().map(f -> rulesDir().relativize(f).toString().replace('\\', '/')).toList();
        assertThat(rel).contains("tron/large-usdt-transfer.yaml", "tron/suspicious-wallet.yaml", "ethereum/contract-exploit.yaml",
                "ethereum/suspicious-approval.yaml", "generic/wallet-draining.yaml", "generic/fund-splitting.yaml");
        assertThat(rulesDir().resolve("README.md")).isRegularFile();
    }

    @Test void ruleIdsAreUnique() throws IOException {
        Set<String> ids = new HashSet<>();
        for (Path f : ruleFiles()) assertThat(ids.add(loader.load(f).id())).as("duplicate id in " + f).isTrue();
    }

    @TestFactory Stream<DynamicTest> everyRuleIsValidDocumentedAndPassesItsOwnTests() throws IOException {
        return ruleFiles().stream().map(file -> DynamicTest.dynamicTest(rulesDir().relativize(file).toString(), () -> {
            RuleLoader.LoadResult lr = loader.tryLoad(Files.readString(file), Format.fromFileName(file.getFileName().toString()));
            assertThat(lr.errors()).as("validation errors in " + file).isEmpty();
            CompiledRule rule = lr.rule();

            String base = file.getFileName().toString().replaceFirst("\\.(ya?ml|json)$", "");
            assertThat(rule.id()).as("file name must be <id>.yaml").isEqualTo(base);
            String folder = file.getParent().getFileName().toString();
            if (!folder.equals("generic")) {
                assertThat(rule.rule().scope().networks()).as("rules in %s/ must be scoped to that network", folder)
                        .contains(Network.fromId(folder));
            }
            assertThat(rule.rule().documentation()).as("documentation").isNotBlank();
            assertThat(rule.rule().author()).isNotBlank();
            assertThat(rule.rule().examples()).as("examples").isNotEmpty();
            assertThat(rule.rule().tests()).as("tests").isNotEmpty();
            assertThat(rule.rule().examples()).as("needs a positive and a negative example")
                    .anyMatch(e -> e.expectMatch()).anyMatch(e -> !e.expectMatch());
            assertThat(rule.rule().tests()).as("needs a triggering test").anyMatch(t -> t.expectTriggers() > 0);

            // rules describe behaviour, never people: no accusatory labels in user-facing text
            String text = String.join(" ", rule.rule().name(), String.valueOf(rule.rule().description()), rule.rule().documentation());
            assertThat(ACCUSATORY.matcher(text).find()).as("accusatory wording in " + file + ": " + text).isFalse();

            EmbeddedTestReport report = tester.runEmbeddedTests(rule);
            assertThat(report.failures()).as("embedded test failures in " + file).isEmpty();
        }));
    }

    static Rule rule(Path file) throws IOException {
        RuleLoader.LoadResult lr = new RuleLoader().tryLoad(Files.readString(file), Format.fromFileName(file.getFileName().toString()));
        assertThat(lr.errors()).as("validation errors in " + file).isEmpty();
        return lr.rule().rule();
    }

    static String frenchText(RuleTranslation fr) {
        return String.join("\n", String.valueOf(fr.name()), String.valueOf(fr.description()), String.valueOf(fr.documentation()));
    }

    @TestFactory Stream<DynamicTest> everyShippedRuleHasAFrenchNameDescriptionAndDocumentation() throws IOException {
        return ruleFiles().stream().map(file -> DynamicTest.dynamicTest(rulesDir().relativize(file).toString(), () -> {
            Rule r = rule(file);
            RuleTranslation fr = r.translations().get("fr");
            assertThat(fr).as("translations.fr in " + file).isNotNull();
            assertThat(fr.name()).as("translations.fr.name in " + file).isNotBlank().isNotEqualTo(r.name());
            assertThat(fr.description()).as("translations.fr.description in " + file).isNotBlank().isNotEqualTo(r.description());
            assertThat(fr.documentation()).as("translations.fr.documentation in " + file).isNotBlank()
                    .isNotEqualTo(r.documentation());
        }));
    }

    @Test void frenchBanListCatchesFeminineAndPluralForms() {
        for (String w : List.of("criminel", "Criminelles", "criminels", "escroc", "escrocs", "voleur", "voleuses", "fraudeurs",
                "fraudeuse", "coupable", "coupables", "illégal", "ILLÉGALE", "illégaux", "illegales", "terroriste", "terroristes",
                "blanchisseur", "blanchisseuses")) {
            assertThat(ACCUSATORY_FR.matcher("un texte " + w + ".").find()).as(w).isTrue();
        }
        for (String w : List.of("blanchiment", "illicite", "volume", "culpabilité", "fraude")) {
            assertThat(ACCUSATORY_FR.matcher("un texte " + w + ".").find()).as(w).isFalse();
        }
    }

    @TestFactory Stream<DynamicTest> frenchTextsAvoidAccusatoryWords() throws IOException {
        return ruleFiles().stream().map(file -> DynamicTest.dynamicTest(rulesDir().relativize(file).toString(), () -> {
            for (RuleTranslation t : rule(file).translations().values()) {
                String text = frenchText(t);
                java.util.regex.Matcher m = ACCUSATORY_FR.matcher(text);
                boolean found = m.find();
                assertThat(found).as("accusatory wording in " + file + ": " + (found ? m.group() : "")).isFalse();
                assertThat(ACCUSATORY.matcher(text).find()).as("accusatory English wording in " + file).isFalse();
            }
        }));
    }

    /** Same rule as the console's dictionaries: U+202F before ? ! ; : and inside « », never straight double quotes. */
    @TestFactory Stream<DynamicTest> frenchTextsUseFrenchTypography() throws IOException {
        return ruleFiles().stream().map(file -> DynamicTest.dynamicTest(rulesDir().relativize(file).toString(), () -> {
            RuleTranslation fr = rule(file).translations().get("fr");
            assertThat(fr).isNotNull();
            String text = frenchText(fr);
            assertThat(text).as("straight double quote in " + file).doesNotContain("\"");
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                String around = text.substring(Math.max(0, i - 30), Math.min(text.length(), i + 10));
                if (";:!?»".indexOf(c) >= 0)
                    assertThat(i > 0 && text.charAt(i - 1) == '\u202F').as("U+202F before '%s' in %s: %s", c, file, around).isTrue();
                if (c == '«')
                    assertThat(i + 1 < text.length() && text.charAt(i + 1) == '\u202F').as("U+202F after « in %s: %s", file, around).isTrue();
            }
        }));
    }

    /** The translations are documentation: the compiled rule is the same with and without them. */
    @TestFactory Stream<DynamicTest> translationsDoNotChangeDetection() throws IOException {
        RuleParser parser = new RuleParser();
        RuleValidator validator = new RuleValidator();
        RuleCompiler compiler = new RuleCompiler();
        return ruleFiles().stream().map(file -> DynamicTest.dynamicTest(rulesDir().relativize(file).toString(), () -> {
            JsonNode node = parser.parse(Files.readString(file), Format.fromFileName(file.getFileName().toString()));
            CompiledRule with = compiler.compile(validator.validate(node).rule());
            assertThat(((ObjectNode) node).remove("translations")).as("translations block in " + file).isNotNull();
            ValidationResult vr = validator.validate(node);
            assertThat(vr.errors()).isEmpty();
            CompiledRule without = compiler.compile(vr.rule());
            assertThat(without.rule().translations()).isEmpty();
            assertThat(with.rule().translations()).isNotEmpty();
            assertThat(with.kind()).isEqualTo(without.kind());
            assertThat(with.rule().conditions()).isEqualTo(without.rule().conditions());
            assertThat(with.rule().aggregation()).isEqualTo(without.rule().aggregation());
            assertThat(with.rule().sequence()).isEqualTo(without.rule().sequence());
            assertThat(with.rule()).usingRecursiveComparison().ignoringFields("translations").isEqualTo(without.rule());
            assertThat(tester.runEmbeddedTests(with).results()).isEqualTo(tester.runEmbeddedTests(without).results());
        }));
    }
}
