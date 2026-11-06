package io.openchain.alerting.testing;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
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
}
