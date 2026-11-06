package io.openchain.core;

import io.openchain.core.model.*;
import io.openchain.core.risk.RiskLevel;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class CoreModelTest {
    @Test void evmAddressesAreCanonicalLowercase() {
        assertThat(new Address(Network.ETHEREUM, "0xABCdef").value()).isEqualTo("0xabcdef");
        assertThat(new Address(Network.TRON, "TXyzAB").value()).isEqualTo("TXyzAB");
    }
    @Test void tokenAmountIsExact() {
        var usdt = new Token(Network.TRON, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", "USDT", 6);
        var t = new TokenTransfer(Network.TRON, "abc", 0, 1, "h", Instant.EPOCH,
                new Address(Network.TRON, "Ta"), new Address(Network.TRON, "Tb"), usdt, BigInteger.valueOf(10_000_500_000L));
        assertThat(t.amount().toPlainString()).isEqualTo("10000.500000");
        assertThat(t.eventId()).isEqualTo("tron:abc:0");
    }
    @Test void riskBands() {
        assertThat(RiskLevel.fromScore(0)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromScore(70)).isEqualTo(RiskLevel.HIGH);
        assertThat(RiskLevel.fromScore(100)).isEqualTo(RiskLevel.CRITICAL);
        assertThatThrownBy(() -> RiskLevel.fromScore(101)).isInstanceOf(IllegalArgumentException.class);
    }
}
