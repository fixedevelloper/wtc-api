package com.wtc.transfer.application.port;

import java.util.Optional;

/** Codes d'un contact chez le fournisseur ; un bénéficiaire a un code par expéditeur. */
public interface ProviderRefRepository {

    Optional<String> find(long partyId, String provider, Long senderPartyId);

    void save(long partyId, String provider, Long senderPartyId, String externalCode);
}
