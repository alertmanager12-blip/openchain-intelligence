package io.openchain.alerting.rule;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whitelist of the fields a rule may reference. Anything not in the catalog is rejected at validation time, so a
 * rule can never reach into arbitrary event data or object graphs. Immutable; extend with {@link #with(FieldDef...)}.
 *
 * <p>Virtual fields derived from the {@code ChainEvent} envelope: {@code network}, {@code event.type},
 * {@code event.block_number}, {@code event.time}. All other fields are read from {@code ChainEvent.fields()}.
 */
public final class FieldCatalog {

    /**
     * @param sourceBacked true when the value comes from an authoritative external source (e.g. official sanctions
     *                     list); only such fields allow a rule to declare {@code evidenceLevel: CONFIRMED_BY_SOURCE}
     */
    public record FieldDef(String name, FieldType type, String description, boolean sourceBacked) {
        public FieldDef {
            if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid field name: " + name);
        }
        public FieldDef(String name, FieldType type, String description) { this(name, type, description, false); }
    }

    public static final Pattern NAME = Pattern.compile("^[a-z][a-z0-9_]{0,40}(\\.[a-z][a-z0-9_]{0,40}){0,5}$");

    private static final FieldCatalog DEFAULTS = new FieldCatalog(Map.of()).with(
            // envelope (virtual)
            new FieldDef("network", FieldType.STRING, "Network id (tron, ethereum, bsc, ...)"),
            new FieldDef("event.type", FieldType.STRING, "ChainEvent type (TOKEN_TRANSFER, APPROVAL, ...)"),
            new FieldDef("event.block_number", FieldType.INTEGER, "Block number of the event"),
            new FieldDef("event.time", FieldType.TIMESTAMP, "On-chain event time"),
            // transaction
            new FieldDef("transaction.hash", FieldType.STRING, "Transaction hash"),
            new FieldDef("transaction.from", FieldType.STRING, "Sender address (canonical form; EVM lowercase)"),
            new FieldDef("transaction.to", FieldType.STRING, "Recipient address (canonical form; EVM lowercase)"),
            new FieldDef("transaction.amount", FieldType.DECIMAL, "Transferred amount in token units (raw / 10^decimals)"),
            new FieldDef("transaction.amount_usd", FieldType.DECIMAL, "Transferred amount valued in USD, when priced"),
            new FieldDef("transaction.fee", FieldType.DECIMAL, "Fee paid in native units"),
            new FieldDef("transaction.token.symbol", FieldType.STRING, "Token symbol (USDT, ETH, TRX...)"),
            new FieldDef("transaction.token.contract", FieldType.STRING, "Token contract address; absent for native coin"),
            new FieldDef("transaction.token.decimals", FieldType.INTEGER, "Token decimals"),
            new FieldDef("transaction.method", FieldType.STRING, "Method selector or decoded method name"),
            new FieldDef("transaction.success", FieldType.BOOLEAN, "Execution success"),
            new FieldDef("transaction.contract_interaction", FieldType.BOOLEAN, "Transaction calls a contract"),
            // wallet (the monitored / sending address)
            new FieldDef("wallet.address", FieldType.STRING, "Wallet the event is attributed to"),
            new FieldDef("wallet.balance", FieldType.DECIMAL, "Wallet balance of the transferred token BEFORE the event"),
            new FieldDef("wallet.risk_score", FieldType.INTEGER, "Explainable risk score 0..100 (not an accusation)"),
            new FieldDef("wallet.risk_level", FieldType.STRING, "UNKNOWN|LOW|MEDIUM|HIGH|CRITICAL"),
            new FieldDef("wallet.age_days", FieldType.INTEGER, "Days since first on-chain activity"),
            new FieldDef("wallet.tx_count", FieldType.INTEGER, "Known transaction count"),
            new FieldDef("wallet.labels", FieldType.STRING, "Comma-separated labels (exchange, bridge, ...)"),
            new FieldDef("wallet.watchlisted", FieldType.BOOLEAN, "Address is on an operator watchlist"),
            new FieldDef("wallet.sanctioned", FieldType.BOOLEAN, "Listed by an official sanctions source", true),
            // counterparty
            new FieldDef("counterparty.address", FieldType.STRING, "Other side of the transfer"),
            new FieldDef("counterparty.risk_score", FieldType.INTEGER, "Counterparty risk score 0..100"),
            new FieldDef("counterparty.labels", FieldType.STRING, "Comma-separated counterparty labels"),
            new FieldDef("counterparty.is_contract", FieldType.BOOLEAN, "Counterparty is a contract"),
            new FieldDef("counterparty.age_days", FieldType.INTEGER, "Counterparty age in days"),
            new FieldDef("counterparty.sanctioned", FieldType.BOOLEAN, "Counterparty listed by an official sanctions source", true),
            // contracts
            new FieldDef("contract.address", FieldType.STRING, "Contract address"),
            new FieldDef("contract.event", FieldType.STRING, "Decoded contract event name (OwnershipTransferred, Upgraded...)"),
            new FieldDef("contract.method", FieldType.STRING, "Decoded method name"),
            new FieldDef("contract.new_owner", FieldType.STRING, "New owner/admin for admin-change events"),
            new FieldDef("contract.verified", FieldType.BOOLEAN, "Source code verified"),
            new FieldDef("contract.age_days", FieldType.INTEGER, "Contract age in days"),
            new FieldDef("contract.outflow_ratio", FieldType.DECIMAL, "Share of contract TVL moved out by the transaction (0..1)"),
            // approvals
            new FieldDef("approval.owner", FieldType.STRING, "Token owner granting the allowance"),
            new FieldDef("approval.spender", FieldType.STRING, "Spender receiving the allowance"),
            new FieldDef("approval.amount", FieldType.DECIMAL, "Allowance in token units"),
            new FieldDef("approval.unlimited", FieldType.BOOLEAN, "Allowance equals the max uint256"),
            new FieldDef("approval.spender_is_contract", FieldType.BOOLEAN, "Spender is a contract"),
            new FieldDef("approval.spender_age_days", FieldType.INTEGER, "Spender age in days"),
            new FieldDef("approval.spender_verified", FieldType.BOOLEAN, "Spender contract source verified"),
            // detection signals forwarded by the intelligence module
            new FieldDef("signal.type", FieldType.STRING, "SignalType name"),
            new FieldDef("signal.severity", FieldType.STRING, "INFO|LOW|MEDIUM|HIGH|CRITICAL"),
            new FieldDef("signal.confidence", FieldType.DECIMAL, "Signal confidence 0..1"),
            new FieldDef("signal.evidence_level", FieldType.STRING, "ANOMALY|RISK_SIGNAL|CONFIRMED_BY_SOURCE"),
            // internal events produced by INTERNAL_EVENT actions (loop protection)
            new FieldDef("internal.hop", FieldType.INTEGER, "Number of alert->event hops that produced this event"),
            new FieldDef("internal.origin_chain", FieldType.STRING, "Comma-separated rule ids that produced this event"),
            new FieldDef("alert.id", FieldType.STRING, "Originating alert id"),
            new FieldDef("alert.rule_id", FieldType.STRING, "Originating rule id"),
            new FieldDef("alert.severity", FieldType.STRING, "Originating alert severity"),
            new FieldDef("alert.group_key", FieldType.STRING, "Originating alert group key"));

    private final Map<String, FieldDef> fields;

    private FieldCatalog(Map<String, FieldDef> fields) { this.fields = Collections.unmodifiableMap(fields); }

    public static FieldCatalog defaults() { return DEFAULTS; }

    public static FieldCatalog empty() { return new FieldCatalog(Map.of()); }

    /** Returns a new catalog with the given fields added (or redefined). */
    public FieldCatalog with(FieldDef... defs) {
        Map<String, FieldDef> copy = new LinkedHashMap<>(fields);
        for (FieldDef d : defs) copy.put(d.name(), d);
        return new FieldCatalog(copy);
    }

    public Optional<FieldDef> find(String name) { return Optional.ofNullable(name == null ? null : fields.get(name)); }

    public boolean contains(String name) { return name != null && fields.containsKey(name); }

    public Collection<FieldDef> all() { return fields.values(); }
}
