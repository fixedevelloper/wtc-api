package com.wtc.transfer.domain;

import static com.wtc.transfer.domain.TransferStatus.CANCELLED;
import static com.wtc.transfer.domain.TransferStatus.COMPLETED;
import static com.wtc.transfer.domain.TransferStatus.CREATED;
import static com.wtc.transfer.domain.TransferStatus.FAILED;
import static com.wtc.transfer.domain.TransferStatus.PAYOUT_PENDING;
import static com.wtc.transfer.domain.TransferStatus.PROCESSING;
import static com.wtc.transfer.domain.TransferStatus.REFUNDED;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Year;
import org.junit.jupiter.api.Test;

class TransferStatusTest {

    @Test
    void theHappyPathAndTheFailurePathsAreAllowed() {
        assertThat(CREATED.canMoveTo(PAYOUT_PENDING)).isTrue();
        assertThat(PAYOUT_PENDING.canMoveTo(PROCESSING)).isTrue();
        assertThat(PROCESSING.canMoveTo(COMPLETED)).isTrue();
        assertThat(PAYOUT_PENDING.canMoveTo(FAILED)).isTrue();
        assertThat(PROCESSING.canMoveTo(FAILED)).isTrue();
        assertThat(FAILED.canMoveTo(REFUNDED)).isTrue();
        assertThat(CREATED.canMoveTo(CANCELLED)).isTrue();
    }

    @Test
    void moneyCanOnlyBeRefundedAfterAConfirmedFailure() {
        for (TransferStatus status : TransferStatus.values()) {
            assertThat(status.canMoveTo(REFUNDED)).as(status.name()).isEqualTo(status == FAILED);
        }
    }

    @Test
    void aCompletedTransferNeverMovesAgain() {
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        for (TransferStatus target : TransferStatus.values()) {
            assertThat(COMPLETED.canMoveTo(target)).isFalse();
            assertThat(REFUNDED.canMoveTo(target)).isFalse();
        }
    }

    @Test
    void nothingGoesBackwardsOrSkipsTheProviderStep() {
        assertThat(PROCESSING.canMoveTo(PAYOUT_PENDING)).isFalse();
        assertThat(CREATED.canMoveTo(COMPLETED)).isFalse();
        assertThat(CREATED.canMoveTo(PROCESSING)).isFalse();
        assertThat(PAYOUT_PENDING.canMoveTo(COMPLETED)).isFalse();
        assertThat(FAILED.canMoveTo(COMPLETED))
                .as("un échec confirmé ne devient pas un succès")
                .isFalse();
        for (TransferStatus status : TransferStatus.values()) {
            assertThat(status.canMoveTo(status))
                    .as("pas de transition vers soi-même : " + status)
                    .isFalse();
        }
    }

    @Test
    void openTransfersAreTheOnesHoldingCustomerMoney() {
        assertThat(CREATED.isOpen()).isTrue();
        assertThat(PAYOUT_PENDING.isOpen()).isTrue();
        assertThat(PROCESSING.isOpen()).isTrue();
        assertThat(FAILED.isOpen()).as("à rembourser, pas « en cours »").isFalse();
        assertThat(COMPLETED.isOpen()).isFalse();
    }

    @Test
    void referencesAreReadableAndPadded() {
        assertThat(TransferReference.format(Year.of(2026), 123)).isEqualTo("WTC-2026-000123");
        assertThat(TransferReference.format(Year.of(2026), 1234567)).isEqualTo("WTC-2026-1234567");
    }

    @Test
    void partyTypePairFollowsTheKinds() {
        assertThat(PartyTypePair.of("INDIVIDUAL", "INDIVIDUAL")).isEqualTo("P2P");
        assertThat(PartyTypePair.of("INDIVIDUAL", "BUSINESS")).isEqualTo("P2B");
        assertThat(PartyTypePair.of("BUSINESS", "INDIVIDUAL")).isEqualTo("B2P");
        assertThat(PartyTypePair.of("BUSINESS", "BUSINESS")).isEqualTo("B2B");
    }
}
