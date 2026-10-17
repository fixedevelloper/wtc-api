package com.wtc.wallet.domain;

import static com.wtc.wallet.domain.Direction.CREDIT;
import static com.wtc.wallet.domain.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.shared.error.DomainException;
import com.wtc.wallet.domain.PostingRules.Line;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostingRulesTest {

    private static Line line(Direction d, String amount, String currency) {
        return new Line(d, new BigDecimal(amount), currency);
    }

    @Test
    void aBalancedTransactionIsAccepted() {
        assertThatCode(
                        () ->
                                PostingRules.requireValid(
                                        List.of(
                                                line(DEBIT, "10500", "XAF"),
                                                line(CREDIT, "10000", "XAF"),
                                                line(CREDIT, "500", "XAF"))))
                .doesNotThrowAnyException();
    }

    @Test
    void balanceIsCheckedPerCurrencyNotInTotal() {
        assertThatThrownBy(
                        () ->
                                PostingRules.requireValid(
                                        List.of(
                                                line(DEBIT, "100", "XAF"),
                                                line(CREDIT, "100", "EUR"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XAF");
        assertThatCode(
                        () ->
                                PostingRules.requireValid(
                                        List.of(
                                                line(DEBIT, "100", "XAF"),
                                                line(CREDIT, "100", "XAF"),
                                                line(DEBIT, "5.00", "EUR"),
                                                line(CREDIT, "5", "EUR"))))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnbalancedOrSingleLineTransactionIsAProgrammingError() {
        assertThatThrownBy(
                        () ->
                                PostingRules.requireValid(
                                        List.of(
                                                line(DEBIT, "100", "XAF"),
                                                line(CREDIT, "99", "XAF"))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PostingRules.requireValid(List.of(line(DEBIT, "100", "XAF"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void amountsMustBePositiveAndFitTheCurrency() {
        assertThatThrownBy(() -> PostingRules.requireAmount(BigDecimal.ZERO, "XAF"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PostingRules.requireAmount(new BigDecimal("-1"), "XAF"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PostingRules.requireAmount(null, "XAF"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PostingRules.requireAmount(new BigDecimal("10.5"), "XAF"))
                .as("XAF n'a pas de décimale")
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PostingRules.requireAmount(new BigDecimal("10.555"), "EUR"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> PostingRules.requireAmount(BigDecimal.TEN, "ZZZ"))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(
                        () -> PostingRules.requireAmount(new BigDecimal("1000000000000000"), "XAF"))
                .as("16 chiffres")
                .isInstanceOf(DomainException.class);
        assertThatCode(() -> PostingRules.requireAmount(new BigDecimal("10.50"), "EUR"))
                .doesNotThrowAnyException();
        assertThatCode(() -> PostingRules.requireAmount(new BigDecimal("10.00"), "XAF"))
                .as("10.00 vaut 10")
                .doesNotThrowAnyException();
    }

    @Test
    void directionsHaveOpposites() {
        assertThat(DEBIT.opposite()).isEqualTo(CREDIT);
        assertThat(CREDIT.opposite()).isEqualTo(DEBIT);
    }
}
