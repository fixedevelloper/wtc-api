package com.wtc.reference.application;

import com.wtc.reference.application.port.ReferenceValueRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Listes de référence d'un transfert : motifs, origines des fonds et liens entre expéditeur et
 * bénéficiaire.
 */
@Service
@Transactional(readOnly = true)
public class ReferenceValuesService {

    private final ReferenceValueRepository values;

    ReferenceValuesService(ReferenceValueRepository values) {
        this.values = values;
    }

    public List<ReferenceValueView> transferPurposes(String partyTypePair) {
        return values.list("TRANSFER_PURPOSE", partyTypePair);
    }

    public List<ReferenceValueView> sourcesOfFunds(String partyTypePair) {
        return values.list("SOURCE_OF_FUNDS", partyTypePair);
    }

    public List<ReferenceValueView> relationships(String partyTypePair) {
        return values.list("RELATIONSHIP", partyTypePair);
    }
}
