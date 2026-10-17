package com.wtc.customer.domain;

import static com.wtc.customer.domain.KycDocumentStatus.APPROVED;
import static com.wtc.customer.domain.KycDocumentStatus.EXPIRED;
import static com.wtc.customer.domain.KycDocumentStatus.PENDING;
import static com.wtc.customer.domain.KycDocumentStatus.REJECTED;
import static com.wtc.customer.domain.KycDocumentType.NATIONAL_ID;
import static com.wtc.customer.domain.KycDocumentType.PASSPORT;
import static com.wtc.customer.domain.KycDocumentType.PROOF_OF_ADDRESS;
import static com.wtc.customer.domain.KycDocumentType.SELFIE;
import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.customer.domain.KycPolicy.Doc;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class KycPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private static KycStatus status(Doc... docs) {
        return KycPolicy.statusOf(List.of(docs), TODAY);
    }

    @Test
    void noDocumentMeansNotSubmitted() {
        assertThat(status()).isEqualTo(KycStatus.NOT_SUBMITTED);
    }

    @Test
    void anApprovedValidIdentityDocumentVerifiesTheCustomer() {
        assertThat(status(new Doc(PASSPORT, APPROVED, null))).isEqualTo(KycStatus.VERIFIED);
        assertThat(status(new Doc(NATIONAL_ID, APPROVED, TODAY)))
                .as("valable jusqu'au jour d'expiration inclus")
                .isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void aSelfieOrProofOfAddressAloneNeverVerifies() {
        assertThat(
                        status(
                                new Doc(SELFIE, APPROVED, null),
                                new Doc(PROOF_OF_ADDRESS, APPROVED, null)))
                .isEqualTo(KycStatus.EXPIRED);
    }

    @Test
    void aPendingSupplementaryDocumentDoesNotDemoteAVerifiedCustomer() {
        assertThat(
                        status(
                                new Doc(PASSPORT, APPROVED, null),
                                new Doc(PROOF_OF_ADDRESS, PENDING, null)))
                .isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void pendingWinsOverRejectedWhenNothingIsApproved() {
        assertThat(status(new Doc(PASSPORT, REJECTED, null), new Doc(NATIONAL_ID, PENDING, null)))
                .isEqualTo(KycStatus.PENDING);
    }

    @Test
    void onlyRejectedDocumentsMeanRejected() {
        assertThat(status(new Doc(PASSPORT, REJECTED, null))).isEqualTo(KycStatus.REJECTED);
    }

    @Test
    void anExpiredIdentityDocumentLeavesTheCustomerExpired() {
        assertThat(status(new Doc(PASSPORT, APPROVED, TODAY.minusDays(1))))
                .isEqualTo(KycStatus.EXPIRED);
        assertThat(status(new Doc(PASSPORT, EXPIRED, null))).isEqualTo(KycStatus.EXPIRED);
    }

    @Test
    void identityDocumentsAreTheOnesThatVerify() {
        for (KycDocumentType type : KycDocumentType.values()) {
            assertThat(status(new Doc(type, APPROVED, null)) == KycStatus.VERIFIED)
                    .as(type.name())
                    .isEqualTo(type.isIdentity());
        }
    }
}
