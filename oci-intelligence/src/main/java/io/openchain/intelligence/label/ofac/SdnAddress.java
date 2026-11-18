package io.openchain.intelligence.label.ofac;

import io.openchain.core.model.Address;
import java.util.List;

/**
 * One sanctioned address of an indexed network, as listed by OFAC (ADR-0027).
 *
 * @param currencies the "Digital Currency Address - X" features it is listed under (e.g. TRX, USDT), in list order
 * @param sdnUid     the SDN entry (DistinctParty FixedRef), stable across publications
 * @param programs   the sanctions programmes of that entry (e.g. CYBER2), in list order
 */
public record SdnAddress(Address address, List<String> currencies, String sdnUid, String name, List<String> programs) {
    public SdnAddress {
        currencies = List.copyOf(currencies);
        programs = List.copyOf(programs);
    }
}
