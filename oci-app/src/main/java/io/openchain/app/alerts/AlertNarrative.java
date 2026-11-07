package io.openchain.app.alerts;

import static io.openchain.core.detection.SignalText.*;

import io.openchain.alerting.alert.Alert;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * One plain-language line explaining why an alert fired, built only from data stored WITH the alert (its evidence and
 * its subject's field snapshot), so it is identical before and after a restart. The technical evidence stays in
 * {@code evidence}. Describes observed behaviour, never the owner of an address.
 */
public final class AlertNarrative {

    private AlertNarrative() {}

    /** @param f triggering event fields (subject snapshot; may be empty for old alerts) */
    public static String explain(Alert a, Map<String, Object> f) {
        Map<String, Object> ev = a.evidence();
        Object kind = ev.get("kind");
        String who = who(a, f);
        String line;
        if ("AGGREGATION".equals(kind)) line = aggregation(ev, f, who);
        else if ("SEQUENCE".equals(kind)) line = sequence(ev, who);
        else line = single(f, who);
        if (line == null) return null;
        line = sentence(line);
        if (a.occurrenceCount() > 1) line = line + " Seen " + a.occurrenceCount() + " times since this alert opened.";
        return line;
    }

    private static String who(Alert a, Map<String, Object> f) {
        String addr = a.subject() != null && a.subject().address() != null ? a.subject().address() : str(f.get("wallet.address"));
        return addr == null ? "The wallet" : shortAddress(addr);
    }

    private static String aggregation(Map<String, Object> ev, Map<String, Object> f, String who) {
        String fn = str(ev.get("function"));
        BigDecimal value = decimal(ev.get("value"));
        String window = words(str(ev.get("window")));
        String sym = str(f.get("transaction.token.symbol"));
        Object n = ev.get("eventCount");
        String events = n == null ? "" : " (" + plural(Long.parseLong(n.toString()), "transfer", "transfers") + ")";
        if (fn == null || value == null) return null;
        return switch (fn) {
            case "RATIO_OF_BALANCE" -> value.compareTo(BigDecimal.ONE) < 0
                    ? who + " sent " + percent(value.doubleValue()) + " of its " + (sym == null ? "" : sym + " ") + "balance within " + window + events
                    : who + " sent more than its whole starting " + (sym == null ? "" : sym + " ") + "balance (" + number(value)
                    + " times it, funds received meanwhile were passed on) within " + window + events;
            case "SUM" -> who + " moved " + amount(value, sym) + " in total within " + window + events
                    + threshold(ev, sym);
            case "MAX" -> who + " made a transfer of " + amount(value, sym) + " within " + window + threshold(ev, sym);
            case "COUNT" -> plural(value.longValue(), "matching event", "matching events") + " for " + who + " within " + window
                    + threshold(ev, null);
            case "DISTINCT_COUNT" -> who + " reached " + value.toPlainString() + " distinct " + fieldWords(str(ev.get("field")))
                    + " within " + window + threshold(ev, null);
            default -> null;
        };
    }

    private static String threshold(Map<String, Object> ev, String sym) {
        BigDecimal t = decimal(ev.get("threshold"));
        return t == null ? "" : " (threshold " + amount(t, sym) + ")";
    }

    private static String sequence(Map<String, Object> ev, String who) {
        Object steps = ev.get("steps");
        int k = steps instanceof List<?> l ? l.size() : 0;
        String span = words(str(ev.get("span")));
        return "The " + k + " steps of the rule's sequence happened in order for " + who + " within " + span;
    }

    private static String single(Map<String, Object> f, String who) {
        if (f.isEmpty()) return null;
        if (f.get("signal.summary") != null) return str(f.get("signal.summary")).replaceAll("\\.$", "")
                + " (" + str(f.getOrDefault("signal.title", f.get("signal.type"))) + " detected on " + who + ")";
        if (f.get("signal.type") != null) return str(f.get("signal.type")) + " detected on " + who;
        if (f.get("freeze.address") != null) {
            boolean freeze = !"UNFREEZE".equals(str(f.get("freeze.action")));
            return "The issuer (" + str(f.get("freeze.issuer")) + ") " + (freeze ? "froze " : "unfroze ")
                    + shortAddress(str(f.get("freeze.address"))) + " in the " + str(f.get("freeze.token")) + " contract";
        }
        if (f.get("admin.contract") != null) {
            return "The " + String.valueOf(f.getOrDefault("admin.role", "owner")).replace('-', ' ') + " of contract "
                    + shortAddress(str(f.get("admin.contract"))) + " changed to " + shortAddress(str(f.get("admin.new")));
        }
        if (f.get("approval.spender") != null) {
            BigDecimal amt = decimal(f.get("approval.amount"));
            return who + " granted " + shortAddress(str(f.get("approval.spender"))) + " an allowance"
                    + (amt == null ? "" : " of " + amount(amt, str(f.get("token.symbol"))));
        }
        BigDecimal amt = decimal(f.get("transaction.amount"));
        if (amt != null && f.get("transaction.from") != null) {
            return shortAddress(str(f.get("transaction.from"))) + " sent " + amount(amt, str(f.get("transaction.token.symbol")))
                    + " to " + shortAddress(str(f.get("transaction.to")));
        }
        return null;
    }

    private static String fieldWords(String field) {
        if (field == null) return "values";
        return switch (field) {
            case "transaction.to", "counterparty.address" -> "recipients";
            case "transaction.from" -> "senders";
            default -> "values of " + field;
        };
    }

    private static String words(String isoDuration) {
        if (isoDuration == null) return "the window";
        try {
            return duration(Duration.parse(isoDuration));
        } catch (DateTimeParseException e) {
            return isoDuration;
        }
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal b) return b;
        try {
            return new BigDecimal(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
