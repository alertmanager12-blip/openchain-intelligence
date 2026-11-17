package io.openchain.intelligence.investigation;

import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.investigation.InvestigationPlan.Assertion;
import io.openchain.intelligence.trace.Trace;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rule drafts proposed from an investigation (ADR-0022 amendment 4). Deterministic, no AI: one fixed template per assertion
 * type ({@link #VERSION}) turns stored assertions into YAML in the format {@code openchain.io/rule/v1}, always
 * {@code enabled: false}, citing the assertions it comes from. A rejected assertion never gives a draft. Checking that a
 * draft is valid and passes its examples, and its dry run, are the caller's (the rule engine lives in another module).
 */
public final class RuleProposals {
    private RuleProposals() {}

    public static final String VERSION = "proposal@1";

    /** NEXT_HOP drafts are kept for the addresses that received the most value only (upper bound of the attribution). */
    public static final int MAX_NEXT_HOPS = 5;

    public enum Template {
        /** TRACE_ATTRIBUTION (outgoing trace): the address value reached sends funds on. */
        NEXT_HOP("next-hop"),
        /** RELATION COMMON_FUNDER: the shared funder sends to other addresses. */
        COMMON_FUNDER("common-funder"),
        /** PATTERN dispersion or peel chain: the subject disperses value again. */
        SUBJECT_DISPERSION("subject-dispersion"),
        /** DIRECT_EXPOSURE: a new transfer between the subject and the listed counterparty. */
        LISTED_COUNTERPARTY("listed-counterparty");

        public final String slug;

        Template(String slug) { this.slug = slug; }
    }

    /**
     * One draft. {@code assertionIds} are the assertions it comes from (several when they lead to the same rule);
     * {@code reviews} their current human decision, absent when undecided.
     */
    public record Proposal(String ruleId, Template template, List<String> assertionIds, Map<String, String> reviews, String yaml) {}

    /** An assertion with a template that gave no draft, and why. */
    public record Skipped(String assertionId, String reason) {}

    public record Result(String version, List<Proposal> proposals, List<Skipped> skipped) {}

    /**
     * @param decisions current review decision per assertion id (ACCEPTED, REJECTED, NEEDS_EVIDENCE); missing = undecided
     * @param flows     the frozen evidence of the investigation (examples are built from it)
     */
    public static Result propose(String investigationId, int version, String network, String subject, List<Assertion> assertions,
                                 Map<String, String> decisions, List<ValueFlow> flows) {
        Map<String, ValueFlow> byId = new LinkedHashMap<>();
        for (ValueFlow f : flows) byId.putIfAbsent(f.eventId(), f);
        List<Assertion> sorted = new ArrayList<>(assertions);
        sorted.sort(Comparator.comparing(Assertion::id));
        Set<String> topHops = topNextHops(sorted, subject);

        // one draft per rule key: assertions leading to the same rule are cited together
        Map<String, Draft> drafts = new LinkedHashMap<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Assertion a : sorted) {
            Template t = template(a);
            if (t == null) continue;
            if ("REJECTED".equals(decisions.get(a.id()))) {
                skipped.add(new Skipped(a.id(), "rejected by a review"));
                continue;
            }
            String key = switch (t) {
                case NEXT_HOP -> a.params().get("address") + "|" + a.params().get("tokenKey");
                case COMMON_FUNDER -> a.params().get("via");
                // a dispersion names its disperser, a peel chain its addresses from the start: the subject must come first
            case SUBJECT_DISPERSION -> subject.equals(a.params().getOrDefault("addresses", "").split(",")[0]) ? subject : null;
                case LISTED_COUNTERPARTY -> a.params().get("counterparty");
            };
            if (t == Template.NEXT_HOP && subject.equals(a.params().get("address"))) {
                skipped.add(new Skipped(a.id(), "the address is the subject itself"));
                continue;
            }
            if (t == Template.NEXT_HOP && !topHops.contains(a.id())) {
                skipped.add(new Skipped(a.id(), "not among the " + MAX_NEXT_HOPS + " addresses that received the most value"));
                continue;
            }
            if (t == Template.SUBJECT_DISPERSION && key == null) {
                skipped.add(new Skipped(a.id(), "the pattern does not start at the subject"));
                continue;
            }
            if (t == Template.COMMON_FUNDER && (key == null || key.isBlank())) {
                skipped.add(new Skipped(a.id(), "no funder named"));
                continue;
            }
            ValueFlow ev = a.evidence().stream().map(byId::get).filter(x -> x != null).findFirst().orElse(null);
            if (ev == null) {
                skipped.add(new Skipped(a.id(), "no frozen transfer among its evidence to build examples from"));
                continue;
            }
            Draft d = drafts.computeIfAbsent(t.name() + "|" + key, k -> new Draft(t, key, ev));
            d.add(a);
            a.evidence().stream().map(byId::get).filter(x -> x != null).forEach(d.evidence::add);
        }

        List<Proposal> out = new ArrayList<>();
        for (Draft d : drafts.values()) {
            String id = "inv-" + hash(d.template.name() + "|" + network + "|" + d.key).substring(0, 12) + "-" + d.template.slug;
            Map<String, String> reviews = new LinkedHashMap<>();
            for (Assertion a : d.assertions) if (decisions.containsKey(a.id())) reviews.put(a.id(), decisions.get(a.id()));
            out.add(new Proposal(id, d.template, d.assertions.stream().map(Assertion::id).toList(), reviews,
                    yaml(id, d, investigationId, version, network, subject)));
        }
        return new Result(VERSION, List.copyOf(out), List.copyOf(skipped));
    }

    /** Ids of the outgoing attributions kept: the largest upper bounds, ties by assertion id (deterministic). */
    static Set<String> topNextHops(List<Assertion> sorted, String subject) {
        List<Assertion> hops = new ArrayList<>();
        for (Assertion a : sorted) if (template(a) == Template.NEXT_HOP && !subject.equals(a.params().get("address"))) hops.add(a);
        hops.sort(Comparator.comparing((Assertion a) -> ratio(a.params().get("high"))).reversed().thenComparing(Assertion::id));
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(MAX_NEXT_HOPS, hops.size()); i++) out.add(hops.get(i).id());
        return out;
    }

    /** An exact fraction "n/d" (or an integer) as a decimal, for ranking only. */
    static BigDecimal ratio(String f) {
        if (f == null || f.isBlank()) return BigDecimal.ZERO;
        try {
            String[] nd = f.split("/");
            BigDecimal n = new BigDecimal(nd[0]);
            return nd.length == 2 ? n.divide(new BigDecimal(nd[1]), java.math.MathContext.DECIMAL128) : n;
        } catch (NumberFormatException | ArithmeticException e) {
            return BigDecimal.ZERO;
        }
    }

    static Template template(Assertion a) {
        Map<String, String> p = a.params();
        return switch (a.type()) {
            case TRACE_ATTRIBUTION -> Trace.Direction.FORWARD.name().equals(p.get("direction")) ? Template.NEXT_HOP : null;
            case RELATION -> "COMMON_FUNDER".equals(p.get("relation")) ? Template.COMMON_FUNDER : null;
            case PATTERN -> Set.of("DISPERSION", "PEEL_CHAIN").contains(p.get("pattern")) ? Template.SUBJECT_DISPERSION : null;
            case DIRECT_EXPOSURE -> Template.LISTED_COUNTERPARTY;
            default -> null;
        };
    }

    private static final class Draft {
        final Template template;
        final String key;
        final ValueFlow example;
        final List<Assertion> assertions = new ArrayList<>();
        /** Frozen transfers cited by its assertions. */
        final List<ValueFlow> evidence = new ArrayList<>();

        Draft(Template template, String key, ValueFlow example) {
            this.template = template;
            this.key = key;
            this.example = example;
        }

        void add(Assertion a) { assertions.add(a); }
    }

    // ---- YAML ---------------------------------------------------------------------------------------------------------

    private static String yaml(String id, Draft d, String investigationId, int version, String network, String subject) {
        Assertion first = d.assertions.getFirst();
        Map<String, String> p = first.params();
        ValueFlow ev = d.example;
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: openchain.io/rule/v1\n");
        y.append("id: ").append(id).append('\n');
        y.append("version: 1.0.0\n");
        String name, description, severity;
        List<String> conditions = new ArrayList<>();
        List<String> groups = new ArrayList<>();
        String aggregation = null;
        List<String> examples = new ArrayList<>();
        String tests = null;
        String other = "0x".regionMatches(true, 0, subject, 0, 2) ? "0x00000000000000000000000000000000000000ff" : "TProposalExampleOther1111111111111";
        switch (d.template) {
            case NEXT_HOP -> {
                String address = p.get("address");
                name = "Funds move on from " + shortAddr(address) + " (proposed from an investigation)";
                description = "Value traced from " + shortAddr(subject) + " reached " + shortAddr(address)
                        + "; alerts when that address sends this token on.";
                severity = "MEDIUM";
                conditions.add(addr("transaction.from", address));
                conditions.add(token(ev));
                conditions.add(positive());
                examples.add(example("the address sends this token on", "MATCH", network, ev, address, other));
                examples.add(example("the transfer that brought the value in is not a move on", "NO_MATCH", network, ev,
                        ev.from().value(), ev.to().value()));
            }
            case COMMON_FUNDER -> {
                String funder = d.key;
                Set<String> known = new LinkedHashSet<>();
                known.add(subject);
                for (Assertion a : d.assertions) known.add(a.params().get("other"));
                name = "Shared funder " + shortAddr(funder) + " funds another address (proposed from an investigation)";
                description = shortAddr(funder) + " funded " + shortAddr(subject) + " and wallets that may be related to it;"
                        + " alerts when it sends to an address other than those.";
                severity = "LOW";
                conditions.add(addr("transaction.from", funder));
                conditions.add(positive());
                groups.add("    - logic: NOT\n      rules:\n        - field: transaction.to\n          operator: IN\n"
                        + (isEvm(funder) ? "          ignoreCase: true\n" : "") + "          value: [" + String.join(", ", known.stream().map(RuleProposals::q).toList()) + "]\n");
                examples.add(example("the funder sends to a new address", "MATCH", network, ev, funder, other));
                examples.add(example("a transfer to a wallet already known is not new", "NO_MATCH", network, ev, funder, subject));
            }
            case SUBJECT_DISPERSION -> {
                // the finding names the disperser; its recipients are the receivers of its evidence transfers
                Set<String> recipients = new LinkedHashSet<>();
                for (ValueFlow f : d.evidence) if (f.from().value().equals(subject) && !f.to().value().equals(subject)) recipients.add(f.to().value());
                int threshold = Math.max(2, recipients.size() - 1);
                name = "Dispersion from " + shortAddr(subject) + " again (proposed from an investigation)";
                description = shortAddr(subject) + " dispersed value to " + recipients.size()
                        + " addresses; alerts when it sends to more than " + threshold + " distinct addresses within one hour.";
                severity = "MEDIUM";
                conditions.add(addr("transaction.from", subject));
                conditions.add(positive());
                groups.add("    - logic: NOT\n      rules:\n        - field: transaction.success\n          operator: EQUALS\n          value: false\n");
                aggregation = "aggregation:\n  window: { preset: 1h }\n  groupBy: [transaction.from]\n  function: DISTINCT_COUNT\n"
                        + "  field: transaction.to\n  operator: GREATER_THAN\n  threshold: " + threshold + "\n";
                examples.add(example("a transfer sent by the subject is counted", "MATCH", network, ev, subject, other));
                examples.add(example("a transfer received by the subject is not", "NO_MATCH", network, ev, other, subject));
                tests = "tests:\n"
                        + "  - name: " + q((threshold + 1) + " distinct recipients within an hour trigger") + "\n"
                        + "    events:\n      - repeat: " + (threshold + 1) + "\n        every: 1m\n" + testEvent(network, ev, subject, other) + "    expectTriggers: 1\n"
                        + "  - name: " + q("repeated payments to one recipient do not") + "\n"
                        + "    events:\n      - repeat: " + (threshold + 3) + "\n        every: 1m\n" + testEvent(network, ev, subject, null) + "    expectTriggers: 0\n";
            }
            case LISTED_COUNTERPARTY -> {
                String cp = d.key;
                name = "New transfer between " + shortAddr(subject) + " and listed " + shortAddr(cp) + " (proposed from an investigation)";
                description = shortAddr(cp) + " is listed as " + p.get("category").toLowerCase().replace('_', ' ') + " by "
                        + p.get("labelSource") + "; alerts on any new transfer between it and " + shortAddr(subject) + ".";
                severity = "SANCTIONED".equals(p.get("category")) ? "HIGH" : "MEDIUM";
                conditions.add(positive());
                groups.add("    - logic: OR\n      groups:\n"
                        + "        - logic: AND\n          rules:\n" + indent(addr("transaction.from", subject), 6) + indent(addr("transaction.to", cp), 6)
                        + "        - logic: AND\n          rules:\n" + indent(addr("transaction.from", cp), 6) + indent(addr("transaction.to", subject), 6));
                examples.add(example("a transfer between them", "MATCH", network, ev, ev.from().value(), ev.to().value()));
                examples.add(example("a transfer of the subject with another address", "NO_MATCH", network, ev, subject, other));
            }
            default -> throw new IllegalStateException();
        }
        y.append("name: ").append(q(name)).append('\n');
        y.append("description: ").append(q(description)).append('\n');
        y.append("author: ").append(q("investigation:" + investigationId + "@v" + version)).append('\n');
        y.append("enabled: false\n");
        y.append("tags: [proposed, investigation, ").append(d.template.slug).append("]\n");
        y.append("scope:\n  networks: [").append(network).append("]\n  eventTypes: [TOKEN_TRANSFER, NATIVE_TRANSFER]\n");
        y.append("conditions:\n  logic: AND\n  rules:\n");
        for (String c : conditions) y.append(c);
        if (!groups.isEmpty()) {
            y.append("  groups:\n");
            for (String g : groups) y.append(g);
        }
        if (aggregation != null) y.append(aggregation);
        y.append("severity: ").append(severity).append('\n');
        y.append("evidenceLevel: ANOMALY\n");
        y.append("cooldown: 1h\n");
        y.append("documentation: ").append(q(documentation(d, investigationId, version))).append('\n');
        y.append("examples:\n");
        for (String e : examples) y.append(e);
        if (tests != null) y.append(tests);
        return y.toString();
    }

    private static String documentation(Draft d, String investigationId, int version) {
        return "Proposed by " + VERSION + " from investigation " + investigationId + " version " + version + ", assertion(s) "
                + String.join(", ", d.assertions.stream().map(Assertion::id).toList()) + ". Created disabled: review it, dry-run it, "
                + "then enable it yourself. The addresses come from inferences (attribution conventions, heuristics), not from an "
                + "identification; an alert from this rule is a lead to check, not a finding.";
    }

    private static boolean isEvm(String a) { return a != null && a.regionMatches(true, 0, "0x", 0, 2); }

    private static String addr(String field, String value) {
        return "    - field: " + field + "\n      operator: EQUALS\n" + (isEvm(value) ? "      ignoreCase: true\n" : "") + "      value: " + q(value) + "\n";
    }

    private static String token(ValueFlow ev) {
        return ev.token().isNative()
                ? "    - field: transaction.token.symbol\n      operator: EQUALS\n      value: " + q(ev.token().symbol()) + "\n"
                : "    - field: transaction.token.contract\n      operator: EQUALS\n" + (isEvm(ev.token().contract()) ? "      ignoreCase: true\n" : "")
                        + "      value: " + q(ev.token().contract()) + "\n";
    }

    private static String positive() { return "    - field: transaction.amount\n      operator: GREATER_THAN\n      value: \"0\"\n"; }

    private static String indent(String block, int n) {
        String pad = " ".repeat(n);
        StringBuilder b = new StringBuilder();
        for (String line : block.split("\n")) b.append(pad).append(line).append('\n');
        return b.toString();
    }

    private static String example(String name, String expect, String network, ValueFlow ev, String from, String to) {
        return "  - name: " + q(name) + "\n    expect: " + expect + "\n    event:\n      type: " + eventType(ev) + "\n      network: " + network
                + "\n      fields:\n" + fields(ev, from, to, "        ");
    }

    private static String testEvent(String network, ValueFlow ev, String from, String to) {
        return "        type: " + eventType(ev) + "\n        network: " + network + "\n        fields:\n"
                + fields(ev, from, to == null ? ev.to().value() : to + "{{i}}", "          ");
    }

    private static String eventType(ValueFlow ev) { return ev.token().isNative() ? "NATIVE_TRANSFER" : "TOKEN_TRANSFER"; }

    private static String fields(ValueFlow ev, String from, String to, String pad) {
        StringBuilder b = new StringBuilder();
        b.append(pad).append("transaction.from: ").append(q(from)).append('\n');
        b.append(pad).append("transaction.to: ").append(q(to)).append('\n');
        b.append(pad).append("transaction.amount: ").append(q(units(ev))).append('\n');
        b.append(pad).append("transaction.token.symbol: ").append(q(ev.token().symbol())).append('\n');
        if (!ev.token().isNative()) b.append(pad).append("transaction.token.contract: ").append(q(ev.token().contract())).append('\n');
        b.append(pad).append("transaction.success: true\n");
        return b.toString();
    }

    private static String units(ValueFlow ev) {
        BigDecimal v = new BigDecimal(ev.rawAmount()).movePointLeft(ev.token().decimals()).stripTrailingZeros();
        return v.signum() == 0 ? "0" : v.toPlainString();
    }

    private static String shortAddr(String a) { return a == null ? "?" : a.length() > 14 ? a.substring(0, 8) + "…" + a.substring(a.length() - 4) : a; }

    /** A double-quoted YAML scalar: backslash, quote and control characters escaped. */
    static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : (s == null ? "" : s).toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\x%02x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static String hash(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
