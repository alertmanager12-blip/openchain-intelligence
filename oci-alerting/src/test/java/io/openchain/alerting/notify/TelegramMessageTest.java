package io.openchain.alerting.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.alerting.alert.Alert;
import io.openchain.alerting.alert.AlertSubject;
import io.openchain.alerting.alert.Priority;
import io.openchain.alerting.rule.ActionType;
import io.openchain.core.detection.Severity;
import io.openchain.core.detection.Signal.EvidenceLevel;
import io.openchain.core.model.Network;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TelegramMessageTest {
    static final Instant T0 = Instant.parse("2026-11-07T20:09:23Z");
    static final String WALLET = "0x6c98a1b2c3d4e5f60718293a4b5c6d7e8f90ee28";
    static final String TX = "0x3f1a00000000000000000000000000000000000000000000000000000000009b2c";

    static Notification drain(AlertSubject subject) {
        Alert a = Alert.builder().id("al-123").ruleId("wallet-draining").ruleVersion("1.2.0")
                .title("Wallet drained (>80% of balance out in 2 minutes)").severity(Severity.HIGH).priority(Priority.P2)
                .evidenceLevel(EvidenceLevel.ANOMALY).network(Network.ETHEREUM).groupKey(WALLET + "|ETH")
                .dedupKey("wallet-draining:" + WALLET).firstSeen(T0).lastSeen(T0).occurrenceCount(1)
                .addEventIds(List.of("e1")).createdAt(T0).updatedAt(T0).subject(subject)
                .evidence(Map.of("kind", "AGGREGATION", "function", "RATIO_OF_BALANCE", "field", "transaction.amount",
                        "value", "0.9996368209589586930535263647230708", "threshold", "0.8", "window", "PT2M", "eventCount", 1))
                .build();
        return Notification.of("d-1", ActionType.TELEGRAM, "tg", Map.of(), a, T0);
    }

    static AlertSubject ethSweep() {
        return new AlertSubject("e1", "NATIVE_TRANSFER", WALLET, "0x" + "a".repeat(40), TX, 26365100L, T0,
                Map.of("transaction.amount", new BigDecimal("11.4"), "transaction.token.symbol", "ETH", "wallet.address", WALLET));
    }

    @Test void detailedFrenchMessageWithExplorerAndConsoleLinks() {
        String m = TelegramMessage.format(drain(ethSweep()), "https://cs.example.com/", 4000);
        assertThat(m).startsWith("🔴 ALERTE HAUTE — Portefeuille vidé\n\n")
                .contains("0x6c98…ee28 a envoyé 99,9 % de son solde ETH en 2 minutes (1 transfert).")
                .contains("Réseau : Ethereum\n", "Montant : 11,4 ETH\n", "Adresse : 0x6c98…ee28\n", "Transaction : 0x3f1a…9b2c\n")
                .contains("🔎 Etherscan : https://etherscan.io/tx/" + TX + "\n")
                .contains("🖥 Console : https://cs.example.com/alerts?id=al-123\n")
                .contains("Règle wallet-draining 1.2.0 · 1 occurrence\n")
                .endsWith(TelegramMessage.DISCLAIMER)
                .doesNotContain("RATIO_OF_BALANCE", "GREATER_THAN", "PT2M");
    }

    @Test void tronTransactionsLinkToTronscanWithoutThe0xPrefix() {
        AlertSubject s = new AlertSubject("e1", "TOKEN_TRANSFER", "TXyzSender", "TXyzReceiver", "abcdef0123", 1L, T0,
                Map.of("transaction.amount", new BigDecimal("2500000"), "transaction.token.symbol", "USDT",
                        "transaction.from", "TXyzSenderAddress000000000000000", "transaction.to", "TXyzReceiverAddress0000000000000"));
        assertThat(TelegramMessage.explorerLink(Network.TRON, s)).isEqualTo("https://tronscan.org/#/transaction/abcdef0123");
        assertThat(TelegramMessage.number(new BigDecimal("2500000"))).isEqualTo("2 500 000");
    }

    @Test void withoutSubjectOrPublicUrlTheMessageStaysValid() {
        String m = TelegramMessage.format(drain(null), null, 4000);
        assertThat(m).startsWith("🔴 ALERTE HAUTE — Portefeuille vidé").contains("Le portefeuille a envoyé 99,9 %")
                .doesNotContain("Console", "Etherscan", "Adresse :");
    }

    @Test void frenchDurationsAndLengthBound() {
        assertThat(TelegramMessage.duration("PT2M")).isEqualTo("2 minutes");
        assertThat(TelegramMessage.duration("PT1H30M")).isEqualTo("1 heure 30 minutes");
        assertThat(TelegramMessage.duration("P1D")).isEqualTo("1 jour");
        assertThat(TelegramMessage.format(drain(ethSweep()), null, 120)).hasSize(120).endsWith("…");
    }
}
