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
    @Override public String toString() { return network.id() + ":" + value; }
}
