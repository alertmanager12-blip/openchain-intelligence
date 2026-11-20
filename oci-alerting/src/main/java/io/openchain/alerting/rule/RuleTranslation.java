package io.openchain.alerting.rule;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A rule's user-facing texts in another language ({@code translations.<language>} of the rule document). Each text is
 * optional: an absent one falls back to the rule's own (English) text, so it is left out of JSON rather than sent
 * empty. Translations are documentation only: they never take part in detection.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleTranslation(String name, String description, String documentation) {

    /** Languages a rule may be translated into (the console's languages other than English). */
    public static final List<String> LANGUAGES = List.of("fr");
}
