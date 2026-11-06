package io.openchain.app.api;

import io.openchain.alerting.rule.ValidationError;
import java.util.List;
import java.util.Locale;

/** Converts rule validation paths ({@code $.conditions.rules[2].field}) to JSON pointers ({@code /conditions/rules/2/field}). */
public final class RuleErrors {

    private RuleErrors() {}

    public static List<ApiException.FieldError> of(List<ValidationError> errors, String pointerPrefix) {
        return errors.stream().map(e -> new ApiException.FieldError(pointerPrefix + pointer(e.path()),
                e.code().name().toLowerCase(Locale.ROOT), e.message())).toList();
    }

    public static String pointer(String path) {
        if (path == null || path.isEmpty() || path.equals("$")) return "";
        String p = path.startsWith("$") ? path.substring(1) : path;
        StringBuilder out = new StringBuilder();
        for (String seg : p.replace("[", ".").replace("]", "").split("\\.")) {
            if (seg.isEmpty()) continue;
            out.append('/').append(seg.replace("~", "~0").replace("/", "~1"));
        }
        return out.toString();
    }
}
