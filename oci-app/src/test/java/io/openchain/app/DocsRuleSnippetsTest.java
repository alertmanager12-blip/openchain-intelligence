package io.openchain.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.rule.RuleLoader;
import io.openchain.alerting.rule.RuleParser.Format;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every rule snippet in the documentation (```yaml blocks with an apiVersion, including heredocs in shell blocks)
 * must be a valid {@code openchain.io/rule/v1} document for the real RuleValidator, and the legacy schema names must
 * be gone. Plain unit test: no Spring context.
 */
class DocsRuleSnippetsTest {

    static final Path ROOT = Path.of("..");
    static final Pattern YAML_BLOCK = Pattern.compile("```yaml\\n(.*?)```", Pattern.DOTALL);
    static final Pattern HEREDOC = Pattern.compile("<<'EOF'\\n(apiVersion:.*?)\\nEOF", Pattern.DOTALL);

    static List<Path> docs() throws IOException {
        List<Path> out = new ArrayList<>(List.of(ROOT.resolve("README.md"), ROOT.resolve("CONTRIBUTING.md"), ROOT.resolve("CHANGELOG.md")));
        try (Stream<Path> s = Files.walk(ROOT.resolve("docs"))) {
            s.filter(p -> p.toString().endsWith(".md")).sorted().forEach(out::add);
        }
        return out;
    }

    @Test
    void everyRuleSnippetInTheDocsValidates() throws IOException {
        RuleLoader loader = new RuleLoader();
        int checked = 0;
        List<String> failures = new ArrayList<>();
        for (Path doc : docs()) {
            String md = Files.readString(doc);
            assertThat(md).as("legacy schema name in %s", doc).doesNotContain("oci.rules/v1").doesNotContain("openchain.io/v1\n");
            for (Pattern p : List.of(YAML_BLOCK, HEREDOC)) {
                Matcher m = p.matcher(md);
                while (m.find()) {
                    String yaml = m.group(1);
                    if (!yaml.contains("apiVersion:")) continue;
                    checked++;
                    RuleLoader.LoadResult r = loader.tryLoad(yaml, Format.YAML);
                    if (!r.valid()) failures.add(ROOT.relativize(doc) + ": " + r.errors());
                }
            }
        }
        System.out.println("DocsRuleSnippetsTest: validated " + checked + " rule snippet(s)");
        assertThat(failures).isEmpty();
        assertThat(checked).as("rule snippets found").isGreaterThanOrEqualTo(7);
    }
}
