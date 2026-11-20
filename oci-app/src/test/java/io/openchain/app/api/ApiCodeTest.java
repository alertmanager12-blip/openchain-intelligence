package io.openchain.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ApiCodeTest {

    private static final Pattern ID = Pattern.compile("[a-z]+(\\.[a-z_]+)+");

    @Test
    void everyCodeIsListedInDocsApiCodesJson() throws Exception {
        String expected = ApiCode.catalogJson(Map.of());
        Path file = Path.of("..", "docs", "api", "codes.json");
        if (!Files.exists(file)) file = Path.of("docs", "api", "codes.json");
        String actual = Files.exists(file) ? Files.readString(file) : "";
        assertThat(actual).as("docs/api/codes.json is out of date; expected content:\n=====\n%s=====\n", expected).isEqualTo(expected);
    }

    @Test
    void idsAreUniqueAndTemplatesUseOnlyDeclaredParams() {
        Set<String> ids = new HashSet<>();
        for (ApiCode c : ApiCode.values()) {
            assertThat(c.id()).matches(ID);
            assertThat(ids.add(c.id())).as("duplicate id %s", c.id()).isTrue();
            String rest = ApiCode.PARAM.matcher(c.template()).replaceAll("");
            assertThat(rest).as("stray brace in %s", c.id()).doesNotContain("{").doesNotContain("}");
            assertThat(c.params()).allMatch(p -> p.matches("[a-z][A-Za-z0-9]*"));
        }
    }

    @Test
    void fillRequiresExactlyTheDeclaredParams() {
        assertThat(ApiCode.NETWORK_NOT_ENABLED.fill(Map.of("network", "bsc"))).isEqualTo("network 'bsc' is not enabled on this instance");
        assertThatThrownBy(() -> ApiCode.NETWORK_NOT_ENABLED.fill(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiCode.NETWORK_NOT_ENABLED.fill(Map.of("network", "x", "other", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Arrays.stream(ApiCode.values()).filter(c -> c.id().startsWith("field.")).count()).isPositive();
    }
}
