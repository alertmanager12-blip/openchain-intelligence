package io.openchain.core.model;

import java.time.Instant;

/**
 * A stablecoin issuer adding an address to (FREEZE) or removing it from (UNFREEZE) the blacklist of its own token
 * contract (e.g. Tether {@code AddedBlackList}, Circle {@code Blacklisted}). Only logs emitted by the issuer's
 * official contract are decoded into this record: anyone can deploy a contract emitting an event with the same name.
 *
 * @param issuer    stable issuer id, e.g. {@code tether}, {@code circle}
 * @param eventName contract event that produced it, e.g. {@code AddedBlackList}
 */
public record TokenFreeze(
        Network network,
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        Instant timestamp,
        Address target,
        Token token,
        String issuer,
        Action action,
        String eventName) {

    public enum Action { FREEZE, UNFREEZE }

    public String eventId() { return network.id() + ":" + txHash + ":" + logIndex; }
}
