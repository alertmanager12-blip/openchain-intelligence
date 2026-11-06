package io.openchain.core.model;

import java.time.Instant;

/**
 * A change of who controls a contract, decoded from one of its own logs:
 * <ul>
 *   <li>{@code OwnershipTransferred(previousOwner, newOwner)} (OpenZeppelin {@code Ownable}; Circle FiatToken declares the
 *       same event with non-indexed parameters): role {@link #ROLE_OWNER};</li>
 *   <li>EIP-1967 {@code Upgraded(implementation)}: role {@link #ROLE_IMPLEMENTATION} (previous value not in the event);</li>
 *   <li>EIP-1967 {@code AdminChanged(previousAdmin, newAdmin)}: role {@link #ROLE_PROXY_ADMIN}.</li>
 * </ul>
 *
 * @param contract  contract that emitted the log (the proxy, for proxied contracts)
 * @param previous  previous owner/admin, null when the event does not carry it ({@code Upgraded})
 * @param newValue  new owner, admin or implementation; the zero address means renounced
 * @param eventName contract event that produced it
 */
public record ContractAdminChange(
        Network network,
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        Instant timestamp,
        Address contract,
        String role,
        Address previous,
        Address newValue,
        String eventName) {

    public static final String ROLE_OWNER = "owner";
    public static final String ROLE_IMPLEMENTATION = "implementation";
    public static final String ROLE_PROXY_ADMIN = "proxy-admin";

    /** Same id space as token transfers ({@code network:txHash:logIndex}). */
    public String eventId() { return network.id() + ":" + txHash + ":" + logIndex; }
}
