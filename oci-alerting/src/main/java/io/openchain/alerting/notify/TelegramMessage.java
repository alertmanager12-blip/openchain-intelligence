package io.openchain.alerting.notify;

import io.openchain.alerting.alert.AlertSubject;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Telegram alert message, in French, readable on a phone: severity, what happened in plain words, the address, amount
 * and transaction, links to the block explorer and to the console, then the rule and the disclaimer.
 * <p>Sent as plain text (no {@code parse_mode}): alert data (token symbols, addresses) can never inject markup.
 * Describes observed behaviour, never the owner of an address.
 */
public final class TelegramMessage {

    static final String DISCLAIMER = "Alerte automatique basée sur le comportement on-chain observé ; "
            + "ce n'est pas une accusation ni une qualification juridique.";

    private TelegramMessage() {}

    /**
     * @param consoleBaseUrl public URL of the console (e.g. {@code https://chainsentinel.example.com}); null or blank =
     *                       no console link
     */
    public static String format(Notification n, String consoleBaseUrl, int maxLength) {
        AlertSubject s = n.subject();
        Map<String, Object> f = s == null ? Map.of() : s.fields();
        String who = who(s, f);
        StringBuilder sb = new StringBuilder();
        sb.append(emoji(n)).append(" ALERTE ").append(level(n)).append(" — ").append(title(n)).append("\n\n");

        String why = explain(n, f, who);
        if (why != null) sb.append(why).append("\n\n");

        if (n.network() != null) sb.append("Réseau : ").append(networkName(n.network())).append('\n');
        BigDecimal amount = decimal(f.get("transaction.amount"));
        String symbol = str(f.get("transaction.token.symbol"));
        if (amount != null) sb.append("Montant : ").append(amount(amount, symbol)).append('\n');
        if (s != null && s.address() != null) sb.append("Adresse : ").append(shortAddress(s.address())).append('\n');
        if (s != null && s.counterparty() != null) sb.append("Contrepartie : ").append(shortAddress(s.counterparty())).append('\n');
        if (s != null && s.txHash() != null) sb.append("Transaction : ").append(shortAddress(s.txHash())).append('\n');
        if (n.occurrenceCount() > 1) sb.append("Occurrences : ").append(n.occurrenceCount()).append('\n');

        String explorer = explorerLink(n.network(), s);
        String console = consoleLink(consoleBaseUrl, n.alertId());
        if (explorer != null || console != null) sb.append('\n');
        if (explorer != null) sb.append("🔎 ").append(explorerName(n.network())).append(" : ").append(explorer).append('\n');
        if (console != null) sb.append("🖥 Console : ").append(console).append('\n');

        sb.append('\n').append("Règle ").append(n.ruleId()).append(' ').append(n.ruleVersion())
                .append(" · ").append(n.occurrenceCount()).append(n.occurrenceCount() > 1 ? " occurrences" : " occurrence").append('\n');
        sb.append(DISCLAIMER);
        return sb.length() > maxLength ? sb.substring(0, maxLength - 1) + "…" : sb.toString();
    }

    // ---- header -----------------------------------------------------------------------------------------------

    private static String emoji(Notification n) {
        return switch (n.severity()) {
            case CRITICAL -> "⛔";
            case HIGH -> "🔴";
            case MEDIUM -> "🟠";
            case LOW -> "🟡";
            default -> "🔵";
        };
    }

    private static String level(Notification n) {
        return switch (n.severity()) {
            case CRITICAL -> "CRITIQUE";
            case HIGH -> "HAUTE";
            case MEDIUM -> "MOYENNE";
            case LOW -> "BASSE";
            default -> "INFO";
        };
    }

    /** French names of the community rules; any other rule keeps its own name. */
    static String title(Notification n) {
        String id = n.ruleId() == null ? "" : n.ruleId();
        return switch (id) {
            case "wallet-draining" -> "Portefeuille vidé";
            case "fund-splitting" -> "Fractionnement de fonds";
            case "large-usdt-transfer" -> "Gros transfert USDT";
            case "suspicious-wallet" -> "Portefeuille au comportement suspect";
            case "issuer-freeze-counterparty" -> "Contrepartie d'une adresse gelée par l'émetteur";
            case "sanctioned-exposure-signal" -> "Exposition à une adresse sanctionnée";
            case "contract-exploit" -> "Possible exploitation de contrat";
            case "dormant-wallet-reactivation" -> "Réveil d'un portefeuille dormant";
            default -> n.title();
        };
    }

    // ---- explanation (same logic as the console's AlertNarrative, in French) ----------------------------------

    static String explain(Notification n, Map<String, Object> f, String who) {
        Map<String, Object> ev = n.evidence();
        Object kind = ev.get("kind");
        String line;
        if ("AGGREGATION".equals(kind)) line = aggregation(ev, f, who);
        else if ("SEQUENCE".equals(kind)) line = sequence(ev, who);
        else line = single(f, who);
        if (line == null) return null;
        return line.endsWith(".") ? line : line + ".";
    }

    private static String who(AlertSubject s, Map<String, Object> f) {
        String a = s != null && s.address() != null ? s.address() : str(f.get("wallet.address"));
        return a == null ? "Le portefeuille" : shortAddress(a);
    }

    private static String aggregation(Map<String, Object> ev, Map<String, Object> f, String who) {
        String fn = str(ev.get("function"));
        BigDecimal value = decimal(ev.get("value"));
        if (fn == null || value == null) return null;
        String window = duration(str(ev.get("window")));
        String sym = str(f.get("transaction.token.symbol"));
        Object k = ev.get("eventCount");
        String events = k == null ? "" : " (" + plural(Long.parseLong(k.toString()), "transfert", "transferts") + ")";
        return switch (fn) {
            case "RATIO_OF_BALANCE" -> value.compareTo(BigDecimal.ONE) < 0
                    ? who + " a envoyé " + percent(value) + " de son solde" + (sym == null ? "" : " " + sym) + " en " + window + events
                    : who + " a envoyé plus que tout son solde" + (sym == null ? "" : " " + sym) + " de départ (" + number(value)
                    + " fois, les fonds reçus entre-temps ont été réexpédiés) en " + window + events;
            case "SUM" -> who + " a transféré " + amount(value, sym) + " au total en " + window + events + threshold(ev, sym);
            case "MAX" -> who + " a effectué un transfert de " + amount(value, sym) + " en " + window + threshold(ev, sym);
            case "COUNT" -> plural(value.longValue(), "événement correspondant", "événements correspondants") + " pour " + who
                    + " en " + window + threshold(ev, null);
            case "DISTINCT_COUNT" -> who + " a atteint " + value.toPlainString() + " " + fieldWords(str(ev.get("field")))
                    + " distincts en " + window + threshold(ev, null);
            default -> null;
        };
    }

    private static String threshold(Map<String, Object> ev, String sym) {
        BigDecimal t = decimal(ev.get("threshold"));
        return t == null ? "" : " (seuil " + amount(t, sym) + ")";
    }

    private static String sequence(Map<String, Object> ev, String who) {
        int k = ev.get("steps") instanceof List<?> l ? l.size() : 0;
        return "Les " + k + " étapes de la séquence de la règle se sont produites dans l'ordre pour " + who + " en "
                + duration(str(ev.get("span")));
    }

    private static String single(Map<String, Object> f, String who) {
        if (f.isEmpty()) return null;
        if (f.get("signal.type") != null) {
            String t = signalTitle(str(f.get("signal.type")));
            return t + " détecté(e) sur " + who;
        }
        if (f.get("freeze.address") != null) {
            boolean freeze = !"UNFREEZE".equals(str(f.get("freeze.action")));
            return "L'émetteur (" + str(f.get("freeze.issuer")) + ") a " + (freeze ? "gelé " : "dégelé ")
                    + shortAddress(str(f.get("freeze.address"))) + " dans le contrat " + str(f.get("freeze.token"));
        }
        if (f.get("admin.contract") != null) {
            return "L'administrateur du contrat " + shortAddress(str(f.get("admin.contract"))) + " a changé : "
                    + shortAddress(str(f.get("admin.new")));
        }
        if (f.get("approval.spender") != null) {
            BigDecimal amt = decimal(f.get("approval.amount"));
            return who + " a accordé à " + shortAddress(str(f.get("approval.spender"))) + " une autorisation de dépense"
                    + (Boolean.TRUE.equals(f.get("approval.unlimited")) ? " illimitée"
                    : amt == null ? "" : " de " + amount(amt, str(f.get("token.symbol"))));
        }
        BigDecimal amt = decimal(f.get("transaction.amount"));
        if (amt != null && f.get("transaction.from") != null) {
            return shortAddress(str(f.get("transaction.from"))) + " a envoyé " + amount(amt, str(f.get("transaction.token.symbol")))
                    + " à " + shortAddress(str(f.get("transaction.to")));
        }
        return null;
    }

    private static String signalTitle(String type) {
        return switch (type) {
            case "SANCTIONED_EXPOSURE" -> "Exposition directe à une adresse sanctionnée";
            case "FLAGGED_EXPOSURE" -> "Exposition directe à une adresse signalée";
            case "INDIRECT_EXPOSURE" -> "Exposition indirecte à une adresse listée";
            case "MIXER_EXPOSURE" -> "Exposition directe à un mixeur";
            case "SCAM_EXPOSURE" -> "Exposition directe à une adresse signalée pour arnaque";
            case "RAPID_DRAIN" -> "Vidage rapide";
            case "DORMANT_REACTIVATION" -> "Réveil d'un portefeuille dormant";
            case "FAST_FORWARDING" -> "Réexpédition rapide des fonds";
            case "STRUCTURING" -> "Possible fractionnement (structuring)";
            case "FLOW_CONCENTRATION" -> "Envoi vers de nombreux destinataires";
            case "UNUSUAL_APPROVAL" -> "Autorisation de dépense illimitée";
            case "OWNERSHIP_CHANGE" -> "Changement de propriétaire de contrat";
            case "UNUSUAL_AMOUNT" -> "Montant inhabituel";
            default -> "Signal " + type;
        };
    }

    private static String fieldWords(String field) {
        if (field == null) return "valeurs";
        return switch (field) {
            case "transaction.to", "counterparty.address" -> "destinataires";
            case "transaction.from" -> "expéditeurs";
            default -> "valeurs de " + field;
        };
    }

    // ---- links ------------------------------------------------------------------------------------------------

    static String explorerLink(Network network, AlertSubject s) {
        if (network == null || s == null) return null;
        String base = switch (network) {
            case ETHEREUM -> "https://etherscan.io";
            case BSC -> "https://bscscan.com";
            case POLYGON -> "https://polygonscan.com";
            case TRON -> "https://tronscan.org/#";
            default -> null;
        };
        if (base == null) return null;
        if (s.txHash() != null && !s.txHash().isBlank()) {
            return base + (network == Network.TRON ? "/transaction/" + strip0x(s.txHash()) : "/tx/" + s.txHash());
        }
        if (s.address() != null && !s.address().isBlank()) return base + "/address/" + s.address();
        return null;
    }

    private static String explorerName(Network network) {
        return switch (network) {
            case ETHEREUM -> "Etherscan";
            case BSC -> "BscScan";
            case POLYGON -> "Polygonscan";
            case TRON -> "Tronscan";
            default -> "Explorateur";
        };
    }

    static String consoleLink(String base, String alertId) {
        if (base == null || base.isBlank() || alertId == null) return null;
        String b = base.strip();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + "/alerts?id=" + java.net.URLEncoder.encode(alertId, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String networkName(Network n) {
        return switch (n) {
            case ETHEREUM -> "Ethereum";
            case TRON -> "TRON";
            case BSC -> "BNB Smart Chain";
            case POLYGON -> "Polygon";
            default -> n.id();
        };
    }

    // ---- French number formatting -----------------------------------------------------------------------------

    private static final DecimalFormatSymbols FR = DecimalFormatSymbols.getInstance(Locale.FRANCE);

    static String number(BigDecimal v) {
        DecimalFormat df = new DecimalFormat(v.abs().compareTo(BigDecimal.ONE) >= 0 ? "#,##0.##" : "#,##0.######", FR);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(v);
    }

    static String amount(BigDecimal v, String symbol) { return number(v) + (symbol == null ? "" : " " + symbol); }

    static String percent(BigDecimal ratio) {
        BigDecimal p = ratio.multiply(BigDecimal.valueOf(100));
        // never show 100 % for a ratio below 1 (99.96 % stays "99,9 %")
        BigDecimal shown = p.compareTo(BigDecimal.valueOf(99)) >= 0 && ratio.compareTo(BigDecimal.ONE) < 0
                ? p.setScale(1, RoundingMode.DOWN) : p.setScale(0, RoundingMode.HALF_UP);
        return number(shown) + " %";
    }

    static String duration(String iso) {
        if (iso == null) return "la fenêtre";
        try {
            Duration d = Duration.parse(iso);
            long h = d.toHours(), m = d.toMinutesPart(), s = d.toSecondsPart();
            if (d.toDays() >= 1 && d.toHoursPart() == 0 && m == 0) return plural(d.toDays(), "jour", "jours");
            StringBuilder sb = new StringBuilder();
            if (h > 0) sb.append(plural(h, "heure", "heures"));
            if (m > 0) sb.append(sb.isEmpty() ? "" : " ").append(plural(m, "minute", "minutes"));
            if (s > 0) sb.append(sb.isEmpty() ? "" : " ").append(plural(s, "seconde", "secondes"));
            return sb.isEmpty() ? "0 seconde" : sb.toString();
        } catch (DateTimeParseException e) {
            return iso;
        }
    }

    private static String plural(long n, String one, String many) { return n + " " + (n <= 1 ? one : many); }

    private static String shortAddress(String a) {
        if (a == null) return "?";
        return a.length() <= 14 ? a : a.substring(0, 6) + "…" + a.substring(a.length() - 4);
    }

    private static String strip0x(String h) { return h.startsWith("0x") ? h.substring(2) : h; }

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
