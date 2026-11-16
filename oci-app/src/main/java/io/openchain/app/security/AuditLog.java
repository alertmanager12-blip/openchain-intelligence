package io.openchain.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code oci.audit} log. Its values come from requests (rule ids, names, addresses, actors): a line break or another
 * control character in one of them could forge a fake audit line, so every argument is written on one line, with control
 * characters escaped ({@code \n}, {@code \r}, {@code \t}, else {@code \\uXXXX}).
 */
public final class AuditLog {
    private final Logger log;

    AuditLog(Logger log) { this.log = log; }

    public static AuditLog of(String name) { return new AuditLog(LoggerFactory.getLogger(name)); }

    public void info(String format, Object... args) {
        if (!log.isInfoEnabled()) return;
        Object[] safe = new Object[args.length];
        for (int i = 0; i < args.length; i++) safe[i] = args[i] == null ? null : oneLine(String.valueOf(args[i]));
        log.info(format, safe);
    }

    static String oneLine(String s) {
        StringBuilder b = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isISOControl(c) && c != ' ' && c != ' ') {
                if (b != null) b.append(c);
                continue;
            }
            if (b == null) b = new StringBuilder(s.length() + 8).append(s, 0, i);
            switch (c) {
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(String.format("\\u%04x", (int) c));
            }
        }
        return b == null ? s : b.toString();
    }
}
