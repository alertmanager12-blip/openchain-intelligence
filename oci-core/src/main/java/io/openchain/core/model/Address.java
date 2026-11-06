package io.openchain.core.model;

import java.util.Objects;

/** A network-scoped address. Value is stored in the network's canonical form (EVM: lowercase hex; TRON: base58). */
public record Address(Network network, String value) {
    public Address {
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || value.length() > 128) throw new IllegalArgumentException("invalid address length");
        value = switch (network) {
            case ETHEREUM, BSC, POLYGON -> value.toLowerCase();
            default -> value;
        };
    }
    /**
     * Base58check of the TRON zero address ({@code 0x41} + 20 zero bytes), the form TRON adapters produce for
     * {@code address(0)} in TVM logs (verified against TronAddress in oci-blockchain's TronAddressTest).
     */
    public static final String TRON_ZERO = "T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb";

    /**
     * True for the zero address ({@code address(0)}), used e.g. by {@code renounceOwnership}: EVM {@code 0x000...0},
     * TRON {@link #TRON_ZERO} (or its hex forms {@code 41000...0} / 40 zeros).
     */
    public boolean isZero() {
        String v = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        if (network == Network.TRON) {
            if (value.equals(TRON_ZERO)) return true;
            if (v.length() == 42 && v.startsWith("41")) v = v.substring(2);
            return v.length() == 40 && v.chars().allMatch(c -> c == '0');
        }
        return (value.startsWith("0x") || value.startsWith("0X")) && !v.isEmpty() && v.chars().allMatch(c -> c == '0');
    }

    @Override public String toString() { return network.id() + ":" + value; }
}
