package io.openchain.blockchain.evm;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.blockchain.token.StablecoinFreezeSources;
import org.junit.jupiter.api.Test;

/** Recomputes every event topic constant used in production with an independent Keccak-256 (test code only). */
class EventTopicsTest {

    @Test void keccakImplementationMatchesPublishedVectors() {
        // Keccak-256 (pre-NIST padding) of "" and "abc", as published for Ethereum's keccak256
        assertThat(Keccak256.hex("")).isEqualTo("0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
        assertThat(Keccak256.hex("abc")).isEqualTo("0x4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45");
        // multi-block input (> 136-byte rate) is absorbed without error and differs from a one-byte change
        assertThat(Keccak256.hex("a".repeat(300))).hasSize(66).isNotEqualTo(Keccak256.hex("a".repeat(299) + "b"));
    }

    @Test void productionTopicConstantsAreKeccakOfTheirEventSignatures() {
        assertThat(EvmCodec.TRANSFER_TOPIC).isEqualTo(Keccak256.hex("Transfer(address,address,uint256)"));
        assertThat(EvmCodec.APPROVAL_TOPIC).isEqualTo(Keccak256.hex("Approval(address,address,uint256)"));
        assertThat(EvmCodec.ADDED_BLACKLIST_TOPIC).isEqualTo(Keccak256.hex("AddedBlackList(address)"));
        assertThat(EvmCodec.REMOVED_BLACKLIST_TOPIC).isEqualTo(Keccak256.hex("RemovedBlackList(address)"));
        assertThat(EvmCodec.BLACKLISTED_TOPIC).isEqualTo(Keccak256.hex("Blacklisted(address)"));
        assertThat(EvmCodec.UNBLACKLISTED_TOPIC).isEqualTo(Keccak256.hex("UnBlacklisted(address)"));
        assertThat(EvmCodec.BLOCK_PLACED_TOPIC).isEqualTo(Keccak256.hex("BlockPlaced(address)"));
        assertThat(EvmCodec.BLOCK_RELEASED_TOPIC).isEqualTo(Keccak256.hex("BlockReleased(address)"));
        // contract admin changes: OpenZeppelin Ownable, EIP-1967 proxies
        assertThat(EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC).isEqualTo(Keccak256.hex("OwnershipTransferred(address,address)"));
        assertThat(EvmCodec.UPGRADED_TOPIC).isEqualTo(Keccak256.hex("Upgraded(address)"));
        assertThat(EvmCodec.ADMIN_CHANGED_TOPIC).isEqualTo(Keccak256.hex("AdminChanged(address,address)"));
        assertThat(EvmCodec.ADMIN_CHANGE_TOPICS).containsExactly(EvmCodec.OWNERSHIP_TRANSFERRED_TOPIC, EvmCodec.UPGRADED_TOPIC,
                EvmCodec.ADMIN_CHANGED_TOPIC);
    }

    @Test void everyFreezeSourceUsesTheTopicOfItsDeclaredEventName() {
        for (StablecoinFreezeSources.Source s : StablecoinFreezeSources.defaults().all()) {
            assertThat(s.freezeTopic()).as(s.toString()).isEqualTo(Keccak256.hex(s.freezeEvent() + "(address)"));
            assertThat(s.unfreezeTopic()).as(s.toString()).isEqualTo(Keccak256.hex(s.unfreezeEvent() + "(address)"));
        }
    }
}
