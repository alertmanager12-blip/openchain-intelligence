package io.openchain.intelligence.detection;

import io.openchain.core.event.ChainEvent;
import io.openchain.core.model.Address;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/**
 * Field names this module reads from {@link ChainEvent#fields()} for APPROVAL, CONTRACT_ADMIN_CHANGE and
 * STABLECOIN_FREEZE / STABLECOIN_UNFREEZE events.
 * Producers (indexer) should emit these keys. Values: addresses as String, amounts as String/Long/BigDecimal/BigInteger.
 */
public final class EventFields {
    private EventFields() {}

    public static final String TX_HASH = "transaction.hash";
    public static final String TOKEN_CONTRACT = "token.contract";
    public static final String TOKEN_SYMBOL = "token.symbol";
    public static final String APPROVAL_OWNER = "approval.owner";
    public static final String APPROVAL_SPENDER = "approval.spender";
    /** Raw (integer, undecimalised) allowance. */
    public static final String APPROVAL_AMOUNT_RAW = "approval.amount_raw";
    public static final String ADMIN_CONTRACT = "admin.contract";
    public static final String ADMIN_PREVIOUS = "admin.previous";
    public static final String ADMIN_NEW = "admin.new";
    public static final String ADMIN_ROLE = "admin.role";
    /** STABLECOIN_FREEZE / STABLECOIN_UNFREEZE. */
    public static final String FREEZE_ADDRESS = "freeze.address";
    public static final String FREEZE_TOKEN = "freeze.token";
    public static final String FREEZE_ISSUER = "freeze.issuer";
    public static final String FREEZE_EVENT = "freeze.event";
    public static final String FREEZE_ACTION = "freeze.action";

    public static Optional<String> string(ChainEvent e, String key) {
        Object v = e.field(key);
        return v == null || v.toString().isBlank() ? Optional.empty() : Optional.of(v.toString().strip());
    }

    public static Optional<Address> address(ChainEvent e, String key) {
        try { return string(e, key).map(s -> new Address(e.network(), s)); }
        catch (IllegalArgumentException ex) { return Optional.empty(); }
    }

    /** Integer amount from String/Long/BigInteger/BigDecimal; empty if absent or not an exact integer. */
    public static Optional<BigInteger> bigInteger(ChainEvent e, String key) {
        Object v = e.field(key);
        try {
            return switch (v) {
                case null -> Optional.empty();
                case BigInteger b -> Optional.of(b);
                case BigDecimal d -> Optional.of(d.toBigIntegerExact());
                case Long l -> Optional.of(BigInteger.valueOf(l));
                case Integer i -> Optional.of(BigInteger.valueOf(i));
                default -> {
                    String s = v.toString().strip();
                    yield Optional.of(s.startsWith("0x") ? new BigInteger(s.substring(2), 16) : new BigDecimal(s).toBigIntegerExact());
                }
            };
        } catch (ArithmeticException | NumberFormatException ex) { return Optional.empty(); }
    }
}
