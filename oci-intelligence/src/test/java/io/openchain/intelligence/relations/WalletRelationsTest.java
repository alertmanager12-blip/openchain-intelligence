package io.openchain.intelligence.relations;

import static io.openchain.intelligence.trace.TraceEngineTest.a;
import static org.assertj.core.api.Assertions.assertThat;

import io.openchain.core.model.Address;
import io.openchain.core.model.Network;
import io.openchain.core.model.Token;
import io.openchain.intelligence.flow.ValueFlow;
import io.openchain.intelligence.label.LabelCategory;
import io.openchain.intelligence.relations.WalletRelations.Type;
import io.openchain.intelligence.trace.TraceEngineTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Phase 2 acceptance: every relation is INFERRED with a confidence and its evidence; services never link wallets. */
class WalletRelationsTest {
    final TraceEngineTest.Src src = new TraceEngineTest.Src();
    final Address A = a("a"), B = a("b"), F = a("f"), C = a("c");
    final WalletRelations.Config cfg = WalletRelations.Config.defaults(Map.of("USDT", new BigDecimal("10")));

    List<WalletRelations.Relation> of(Address subject, Type t) {
        return WalletRelations.find(subject, src, cfg).relations().stream().filter(r -> r.type() == t).toList();
    }

    @Test void aCommonFunderWithinTheWindowRelatesTwoWallets() {
        ValueFlow fa = src.flow(F, A, 500, 0), fb = src.flow(F, B, 500, 30);
        assertThat(of(A, Type.COMMON_FUNDER)).singleElement().satisfies(r -> {
            assertThat(r.other()).isEqualTo(B);
            assertThat(r.via()).isEqualTo(F);
            assertThat(r.level()).isEqualTo("INFERRED");
            assertThat(r.confidence()).isBetween(0.5, 0.8);
            assertThat(r.eventIds()).containsExactlyInAnyOrder(fa.eventId(), fb.eventId());
            assertThat(r.explanation()).contains("not proof of common ownership");
        });
    }

    @Test void noCommonFunderOutsideTheWindowOrFromADistributorOrAnExchange() {
        src.flow(F, A, 500, 0);
        src.flow(F, B, 500, 60 * 25); // 25 h later
        assertThat(of(A, Type.COMMON_FUNDER)).isEmpty();

        TraceEngineTest.Src many = new TraceEngineTest.Src();
        many.flow(F, A, 500, 0);
        for (int i = 0; i < 21; i++) many.flow(F, a("d" + i), 500, 1); // payroll / airdrop
        assertThat(WalletRelations.find(A, many, cfg).relations()).isEmpty();

        TraceEngineTest.Src exchange = new TraceEngineTest.Src();
        exchange.label(F, LabelCategory.EXCHANGE);
        exchange.flow(F, A, 500, 0);
        exchange.flow(F, B, 500, 1);
        assertThat(WalletRelations.find(A, exchange, cfg).relations()).isEmpty();
    }

    @Test void dustAndUnverifiedTokensLinkNobody() {
        Token fake = new Token(Network.ETHEREUM, "0xfake000000000000000000000000000000000001", Token.UNKNOWN_SYMBOL, 0);
        src.flow(F, A, 1, 0);       // dust (USDT threshold 10)
        src.flow(F, B, 1, 1);
        src.flow(C, A, 900, fake, 0);
        src.flow(C, B, 900, fake, 1);
        assertThat(WalletRelations.find(A, src, cfg).relations()).isEmpty();
    }

    @Test void aCommonSinkRelatesWalletsThatSendMostOfTheirValueThere() {
        src.flow(A, C, 900, 0); src.flow(A, a("x1"), 100, 1);
        src.flow(B, C, 700, 2); src.flow(B, a("x2"), 300, 3);
        src.flow(a("e"), C, 50, 4); src.flow(a("e"), a("x3"), 950, 5); // sends little to C: not related
        assertThat(of(A, Type.COMMON_SINK)).singleElement().satisfies(r -> {
            assertThat(r.other()).isEqualTo(B);
            assertThat(r.via()).isEqualTo(C);
            assertThat(r.eventIds()).hasSize(2);
        });
    }

    @Test void noCommonSinkThroughAService() {
        src.label(C, LabelCategory.EXCHANGE); // everybody deposits to the same exchange
        src.flow(A, C, 900, 0);
        src.flow(B, C, 900, 1);
        assertThat(of(A, Type.COMMON_SINK)).isEmpty();
    }

    @Test void sharedCounterpartiesNeedAtLeastThree() {
        for (String r : List.of("r1", "r2", "r3")) { src.flow(A, a(r), 100, 0); src.flow(B, a(r), 100, 1); }
        src.flow(A, a("r4"), 100, 2);
        assertThat(of(A, Type.SHARED_COUNTERPARTIES)).singleElement().satisfies(r -> {
            assertThat(r.other()).isEqualTo(B);
            assertThat(r.confidence()).isEqualTo(Math.round((0.2 + 0.6 * 3 / 4.0) * 100) / 100.0);
            assertThat(r.eventIds()).hasSize(6);
        });

        TraceEngineTest.Src two = new TraceEngineTest.Src();
        for (String r : List.of("r1", "r2")) { two.flow(A, a(r), 100, 0); two.flow(B, a(r), 100, 1); }
        two.flow(A, a("r3"), 100, 0);
        assertThat(WalletRelations.find(A, two, cfg).relations()).isEmpty();
    }

    @Test void aServiceSubjectHasNoRelationsAndEveryRelationIsInferred() {
        src.flow(F, A, 500, 0); src.flow(F, B, 500, 1);
        assertThat(WalletRelations.find(A, src, cfg).relations()).isNotEmpty().allMatch(r -> r.level().equals("INFERRED")
                && r.confidence() > 0 && r.confidence() <= 0.95 && !r.eventIds().isEmpty());
        src.label(A, LabelCategory.EXCHANGE);
        assertThat(WalletRelations.find(A, src, cfg).relations()).isEmpty();
    }

    @Test void theSameFlowsGiveTheSameRelations() {
        src.flow(F, A, 500, 0); src.flow(F, B, 500, 1); src.flow(F, C, 400, 2);
        var first = WalletRelations.find(A, src, cfg);
        java.util.Collections.reverse(src.flows);
        assertThat(WalletRelations.find(A, src, cfg)).isEqualTo(first);
    }
}
