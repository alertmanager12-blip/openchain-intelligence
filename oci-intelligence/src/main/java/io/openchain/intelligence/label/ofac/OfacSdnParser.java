package io.openchain.intelligence.label.ofac;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads OFAC's {@code SDN_ADVANCED.XML} as a stream (the document is about 130 MB) and keeps the "Digital Currency Address"
 * features of the networks this instance indexes (ADR-0027):
 * <ul>
 *   <li>TRON: {@code T…} addresses of 34 characters listed under TRX, USDT or USDC;</li>
 *   <li>Ethereum: {@code 0x…} addresses listed under ETH, USDT or USDC. An address listed only under another EVM chain
 *       (BSC, ARB, ETC…) is not applied to Ethereum: same key format, but OFAC named another chain.</li>
 * </ul>
 * The SDN export is not the CSV: its remarks column is cut at 1,000 characters and misses most addresses.
 */
public final class OfacSdnParser {
    private static final String PREFIX = "Digital Currency Address - ";
    private static final Pattern TRON = Pattern.compile("T[1-9A-HJ-NP-Za-km-z]{33}");
    private static final Pattern EVM = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Set<String> TRON_CURRENCIES = Set.of("TRX", "USDT", "USDC");
    private static final Set<String> ETHEREUM_CURRENCIES = Set.of("ETH", "USDT", "USDC");

    private OfacSdnParser() {}

    private record Found(Address address, String currency, String profileId, String sdnUid, String name) {}

    public static SdnPublication parse(InputStream in) {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        try {
            XMLStreamReader r = f.createXMLStreamReader(in);
            return read(r);
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("not an OFAC SDN advanced XML document: " + e.getMessage(), e);
        }
    }

    private static SdnPublication read(XMLStreamReader r) throws XMLStreamException {
        Map<String, String> currencyByFeatureType = new HashMap<>();
        Map<String, List<String>> programsByProfile = new HashMap<>();
        List<Found> found = new ArrayList<>();
        int[] date = new int[3];
        boolean root = false;
        String sdnUid = null, profileId = null, name = null, featureType = null, entryProfile = null, measureType = null;
        boolean inPrimaryAlias = false, nameDone = false;
        StringBuilder primaryName = new StringBuilder();
        String fallbackName = null;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev != XMLStreamConstants.START_ELEMENT) {
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    switch (r.getLocalName()) {
                        case "Alias" -> { if (inPrimaryAlias && !primaryName.isEmpty()) nameDone = true; inPrimaryAlias = false; }
                        case "Feature" -> featureType = null;
                        case "DistinctParty" -> { sdnUid = null; profileId = null; }
                        case "SanctionsEntry" -> entryProfile = null;
                        case "SanctionsMeasure" -> measureType = null;
                        default -> { }
                    }
                }
                continue;
            }
            switch (r.getLocalName()) {
                case "Sanctions" -> root = true;
                case "Year" -> date[0] = date[0] == 0 ? Integer.parseInt(r.getElementText().strip()) : date[0];
                case "Month" -> date[1] = date[1] == 0 ? Integer.parseInt(r.getElementText().strip()) : date[1];
                case "Day" -> date[2] = date[2] == 0 ? Integer.parseInt(r.getElementText().strip()) : date[2];
                case "FeatureType" -> {
                    String id = r.getAttributeValue(null, "ID"), text = r.getElementText().strip();
                    if (text.startsWith(PREFIX)) currencyByFeatureType.put(id, text.substring(PREFIX.length()).strip());
                }
                case "DistinctParty" -> {
                    sdnUid = r.getAttributeValue(null, "FixedRef");
                    primaryName.setLength(0); nameDone = false; fallbackName = null; name = null;
                }
                case "Profile" -> profileId = r.getAttributeValue(null, "ID");
                case "Alias" -> inPrimaryAlias = !nameDone && "true".equals(r.getAttributeValue(null, "Primary"));
                case "DocumentedName" -> { if (inPrimaryAlias && !primaryName.isEmpty()) inPrimaryAlias = false; }
                case "NamePartValue" -> {
                    String part = r.getElementText().strip();
                    if (fallbackName == null) fallbackName = part;
                    if (inPrimaryAlias) { if (!primaryName.isEmpty()) primaryName.append(' '); primaryName.append(part); }
                }
                case "Feature" -> featureType = r.getAttributeValue(null, "FeatureTypeID");
                case "VersionDetail" -> {
                    String currency = featureType == null ? null : currencyByFeatureType.get(featureType);
                    String value = r.getElementText().strip();
                    if (currency != null && sdnUid != null) {
                        Address a = toAddress(value, currency);
                        name = !primaryName.isEmpty() ? primaryName.toString() : fallbackName;
                        if (a != null) found.add(new Found(a, currency, profileId, sdnUid, name == null ? "" : name));
                    }
                }
                case "SanctionsEntry" -> entryProfile = r.getAttributeValue(null, "ProfileID");
                case "SanctionsMeasure" -> measureType = r.getAttributeValue(null, "SanctionsTypeID");
                case "Comment" -> {
                    if (entryProfile != null && "1".equals(measureType)) {
                        String program = r.getElementText().strip();
                        if (!program.isEmpty()) programsByProfile.computeIfAbsent(entryProfile, k -> new ArrayList<>()).add(program);
                    }
                }
                default -> { }
            }
        }
        if (!root || date[0] == 0) throw new IllegalArgumentException("not an OFAC SDN advanced XML document (no Sanctions root or DateOfIssue)");
        Map<Address, SdnAddress> byAddress = new LinkedHashMap<>();
        for (Found x : found) {
            List<String> programs = programsByProfile.getOrDefault(x.profileId(), List.of());
            byAddress.merge(x.address(), new SdnAddress(x.address(), List.of(x.currency()), x.sdnUid(), x.name(), programs),
                    (a, b) -> a.currencies().contains(x.currency()) ? a
                            : new SdnAddress(a.address(), concat(a.currencies(), x.currency()), a.sdnUid(), a.name(), a.programs()));
        }
        return new SdnPublication(LocalDate.of(date[0], date[1], date[2]), new ArrayList<>(byAddress.values()));
    }

    private static List<String> concat(List<String> l, String s) {
        List<String> out = new ArrayList<>(l); out.add(s); return out;
    }

    /** The address on an indexed network, or null when the value or the listed currency does not belong to one. */
    static Address toAddress(String value, String currency) {
        if (TRON.matcher(value).matches() && TRON_CURRENCIES.contains(currency)) return new Address(Network.TRON, value);
        if (EVM.matcher(value).matches() && ETHEREUM_CURRENCIES.contains(currency)) return new Address(Network.ETHEREUM, value);
        return null;
    }
}
