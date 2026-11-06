package io.openchain.intelligence.profile;

import io.openchain.core.model.Address;

/** How often the wallet exchanged value with a counterparty. */
public record CounterpartyStats(Address counterparty, long transfers, long received, long sent) {}
