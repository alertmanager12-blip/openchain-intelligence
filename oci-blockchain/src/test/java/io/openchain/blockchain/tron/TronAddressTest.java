package io.openchain.blockchain.tron;

import static org.assertj.core.api.Assertions.*;

import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TronAddressTest {
    @Test void knownVectors() {
        assertThat(TronAddress.toHex("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t")).isEqualTo("41a614f803b6fd780986a42c78ec9c7f77e6ded13c");
        assertThat(TronAddress.fromHex("41a614f803b6fd780986a42c78ec9c7f77e6ded13c")).isEqualTo("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
        assertThat(TronAddress.fromHex("0xa614f803b6fd780986a42c78ec9c7f77e6ded13c")).isEqualTo("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
        // TRON "black hole" (all-zero) address
        assertThat(TronAddress.fromHex("410000000000000000000000000000000000000000")).isEqualTo("T9yD14Nj9j7xAB4dbGeiX9h8unkKHxuWwb");
    }

    @Test void zeroAddressBase58MatchesTheCoreConstantUsedForRenouncements() {
        assertThat(TronAddress.fromHex("0".repeat(40))).isEqualTo(io.openchain.core.model.Address.TRON_ZERO);
        assertThat(TronAddress.fromHex("41" + "0".repeat(40))).isEqualTo(io.openchain.core.model.Address.TRON_ZERO);
        var tron = io.openchain.core.model.Network.TRON;
        assertThat(new io.openchain.core.model.Address(tron, io.openchain.core.model.Address.TRON_ZERO).isZero()).isTrue();
        assertThat(new io.openchain.core.model.Address(tron, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t").isZero()).isFalse();
        var eth = io.openchain.core.model.Network.ETHEREUM;
        assertThat(new io.openchain.core.model.Address(eth, "0x" + "0".repeat(40)).isZero()).isTrue();
        assertThat(new io.openchain.core.model.Address(eth, "0x" + "0".repeat(39) + "1").isZero()).isFalse();
    }

    @Test void topicDecoding() {
        assertThat(TronAddress.fromTopic("000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c"))
                .isEqualTo("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t");
    }

    @Test void roundTripRandomAddresses() {
        SplittableRandom r = new SplittableRandom(42);
        for (int i = 0; i < 200; i++) {
            StringBuilder hex = new StringBuilder("41");
            for (int j = 0; j < 20; j++) hex.append(String.format("%02x", r.nextInt(256)));
            String b58 = TronAddress.fromHex(hex.toString());
            assertThat(b58).startsWith("T").hasSize(34);
            assertThat(TronAddress.toHex(b58)).isEqualTo(hex.toString());
        }
    }

    @Test void rejectsBadChecksumAndCharacters() {
        assertThat(TronAddress.isValid("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6u")).isFalse();
        assertThat(TronAddress.isValid("TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj60")).isFalse(); // '0' not in alphabet
        assertThat(TronAddress.isValid("1BoatSLRHtKNngkdXEeobR76b53LETtpyT")).isFalse(); // valid BTC, wrong prefix
        assertThatThrownBy(() -> TronAddress.fromHex("42a614f803b6fd780986a42c78ec9c7f77e6ded13c")).isInstanceOf(IllegalArgumentException.class);
    }
}
