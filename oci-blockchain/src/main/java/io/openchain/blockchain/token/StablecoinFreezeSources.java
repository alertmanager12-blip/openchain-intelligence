package io.openchain.blockchain.token;

import static io.openchain.blockchain.evm.EvmCodec.*;

import io.openchain.core.model.Network;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Official stablecoin contracts whose blacklist events are decoded as issuer freezes. A log is a freeze ONLY when it
 * comes from one of these addresses: anyone can deploy a contract that emits {@code AddedBlackList(address)}.
 *
 * <p>Event names / parameters were checked against the issuers' verified sources (2026-11-06):
 * <ul>
 *   <li>Tether USDT, Ethereum {@code 0xdac17f958d2ee523a2206206994597c13d831ec7} (TetherToken, solc 0.4.18):
 *       {@code AddedBlackList(address _user)}, {@code RemovedBlackList(address _user)}, parameter NOT indexed.
 *       Source: https://etherscan.io/address/0xdac17f958d2ee523a2206206994597c13d831ec7#code (verified ABI).</li>
 *   <li>Tether USDT, TRON {@code TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t} (contract name TetherToken on Tronscan): same
 *       BlackList events as the Ethereum deployment (https://blocksec.com/blog/usdt-blacklist-explained-2026). The
 *       Tronscan source page could not be fetched by us; the decoder accepts the address either as topic 1 or as
 *       the single data word, so the indexed/non-indexed variant does not matter.</li>
 *   <li>Tether USDT0, Polygon {@code 0xc2132d05d31c914a87c6611c10748aeb04b58e8f} (UChildERC20Proxy -> UChildUSDT0,
 *       upgraded to USDT0 in 2025): {@code BlockPlaced(address indexed _user)}, {@code BlockReleased(address indexed
 *       _user)}. Source: verified ABI of implementation 0x90040487a6c9f949c4f07cadcfb0f3b8eeab4229 on Polygonscan.</li>
 *   <li>Circle USDC, Ethereum {@code 0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48} and Polygon (native)
 *       {@code 0x3c499c542cef5e3811e1192ce70d8cc03d5c3359} (FiatTokenProxy -> FiatTokenV2_2):
 *       {@code Blacklisted(address indexed _account)}, {@code UnBlacklisted(address indexed _account)}. Source:
 *       https://github.com/circlefin/stablecoin-evm/blob/master/contracts/v1/Blacklistable.sol</li>
 * </ul>
 * Not covered: BSC USDT/USDC (Binance-Peg BEP20 tokens: no issuer blacklist), Polygon USDC.e (bridged), TRON USDC
 * (Circle discontinued it in 2024; event names not verified). Extend with {@link #register}.
 */
public final class StablecoinFreezeSources {

    /**
     * @param labelSource source id of the label created by a freeze (e.g. {@code tether-onchain})
     */
    public record Source(Network network, String contract, String issuer, String labelSource,
                         String freezeTopic, String freezeEvent, String unfreezeTopic, String unfreezeEvent) {}

    private final Map<String, Source> sources = new ConcurrentHashMap<>();

    public static StablecoinFreezeSources defaults() {
        StablecoinFreezeSources s = new StablecoinFreezeSources();
        s.register(tether(Network.ETHEREUM, "0xdac17f958d2ee523a2206206994597c13d831ec7"));
        s.register(tether(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t"));
        s.register(new Source(Network.POLYGON, "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", "tether", "tether-onchain",
                BLOCK_PLACED_TOPIC, "BlockPlaced", BLOCK_RELEASED_TOPIC, "BlockReleased"));
        s.register(circle(Network.ETHEREUM, "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"));
        s.register(circle(Network.POLYGON, "0x3c499c542cef5e3811e1192ce70d8cc03d5c3359"));
        return s;
    }

    public static StablecoinFreezeSources none() { return new StablecoinFreezeSources(); }

    public static Source tether(Network n, String contract) {
        return new Source(n, contract, "tether", "tether-onchain", ADDED_BLACKLIST_TOPIC, "AddedBlackList",
                REMOVED_BLACKLIST_TOPIC, "RemovedBlackList");
    }

    public static Source circle(Network n, String contract) {
        return new Source(n, contract, "circle", "circle-onchain", BLACKLISTED_TOPIC, "Blacklisted",
                UNBLACKLISTED_TOPIC, "UnBlacklisted");
    }

    public StablecoinFreezeSources register(Source s) {
        Source c = new Source(s.network(), TokenRegistry.canonical(s.network(), s.contract()), s.issuer(), s.labelSource(),
                s.freezeTopic(), s.freezeEvent(), s.unfreezeTopic(), s.unfreezeEvent());
        sources.put(key(c.network(), c.contract()), c);
        return this;
    }

    public Optional<Source> find(Network network, String contract) {
        if (contract == null) return Optional.empty();
        return Optional.ofNullable(sources.get(key(network, TokenRegistry.canonical(network, contract))));
    }

    public Collection<Source> forNetwork(Network network) {
        return sources.values().stream().filter(s -> s.network() == network).toList();
    }

    /** Distinct freeze/unfreeze topics used on a network (for log filters). */
    public Set<String> topics(Network network) {
        Set<String> t = new LinkedHashSet<>();
        for (Source s : forNetwork(network)) { t.add(s.freezeTopic()); t.add(s.unfreezeTopic()); }
        return t;
    }

    public List<Source> all() { return List.copyOf(sources.values()); }

    private static String key(Network n, String contract) { return n.id() + ":" + contract; }
}
