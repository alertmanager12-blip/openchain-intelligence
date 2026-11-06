package io.openchain.app.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** API permissions (docs/api "Authentication") and the role -> permission mapping of ADR-0010. */
public final class Scopes {

    public static final String WALLET_READ = "wallet:read";
    public static final String TRANSACTION_READ = "transaction:read";
    public static final String RISK_READ = "risk:read";
    public static final String INVESTIGATION_READ = "investigation:read";
    public static final String ALERTS_READ = "alerts:read";
    public static final String ALERTS_WRITE = "alerts:write";
    public static final String RULES_READ = "rules:read";
    public static final String RULES_WRITE = "rules:write";
    public static final String WEBHOOKS_READ = "webhooks:read";
    public static final String WEBHOOKS_WRITE = "webhooks:write";
    public static final String LABELS_REPORT = "labels:report";
    /** API keys, users, operational endpoints. */
    public static final String KEYS_ADMIN = "keys:admin";

    public static final List<String> ALL = List.of(WALLET_READ, TRANSACTION_READ, RISK_READ, INVESTIGATION_READ, ALERTS_READ,
            ALERTS_WRITE, RULES_READ, RULES_WRITE, WEBHOOKS_READ, WEBHOOKS_WRITE, LABELS_REPORT, KEYS_ADMIN);

    public static final List<String> READ = List.of(WALLET_READ, TRANSACTION_READ, RISK_READ, INVESTIGATION_READ, ALERTS_READ,
            RULES_READ, WEBHOOKS_READ);

    private Scopes() {}

    public static String authority(String scope) { return "SCOPE_" + scope; }

    /** Normalises a scope list; {@code *} expands to every scope. Unknown scopes are rejected. */
    public static Set<String> normalise(Collection<String> scopes) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : scopes) {
            String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
            if (v.equals("*")) { out.addAll(ALL); continue; }
            if (!ALL.contains(v)) throw new IllegalArgumentException("unknown permission '" + s + "'");
            out.add(v);
        }
        return out;
    }

    /** VIEWER (read), ANALYST (+ alerts:write, labels:report), RULE_AUTHOR (+ rules:write), ADMIN (everything). */
    public static Set<String> forRole(String role) {
        Set<String> s = new LinkedHashSet<>();
        switch (role.trim().toUpperCase(Locale.ROOT)) {
            case "ADMIN" -> s.addAll(ALL);
            case "RULE_AUTHOR" -> { s.addAll(forRole("ANALYST")); s.add(RULES_WRITE); }
            case "ANALYST" -> { s.addAll(forRole("VIEWER")); s.add(ALERTS_WRITE); s.add(LABELS_REPORT); }
            case "VIEWER" -> s.addAll(READ);
            default -> { }
        }
        return s;
    }

    public static List<GrantedAuthority> authorities(Collection<String> scopes) {
        List<GrantedAuthority> out = new ArrayList<>();
        for (String s : scopes) out.add(new SimpleGrantedAuthority(authority(s)));
        return out;
    }

    /** Scopes held by an authentication (SCOPE_ authorities). */
    public static Set<String> of(Collection<? extends GrantedAuthority> authorities) {
        Set<String> out = new LinkedHashSet<>();
        for (GrantedAuthority a : authorities) {
            String v = a.getAuthority();
            if (v != null && v.startsWith("SCOPE_")) out.add(v.substring(6));
        }
        return out;
    }
}
