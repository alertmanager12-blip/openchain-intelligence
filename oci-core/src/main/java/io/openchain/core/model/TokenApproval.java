package io.openchain.core.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

/**
 * A fungible-token allowance set by {@code owner} for {@code spender}: ERC-20 / TRC-20
 * {@code Approval(address indexed owner, address indexed spender, uint256 value)} log.
 */
public record TokenApproval(
        Network network,
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        Instant timestamp,
        Address owner,
        Address spender,
        Token token,
        BigInteger rawAmount) {

    /** 2^256 - 1, the value wallets and dapps use for an "unlimited" allowance. */
    public static final BigInteger MAX_UINT256 = BigInteger.TWO.pow(256).subtract(BigInteger.ONE);

    public BigDecimal amount() { return new BigDecimal(rawAmount, token.decimals()); }

    /** True when the allowance is exactly max uint256. */
    public boolean isUnlimited() { return MAX_UINT256.equals(rawAmount); }

    /** Same id space as token transfers ({@code network:txHash:logIndex}): log indexes are unique within a tx. */
    public String eventId() { return network.id() + ":" + txHash + ":" + logIndex; }
}
