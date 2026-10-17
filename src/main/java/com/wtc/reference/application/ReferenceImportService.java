package com.wtc.reference.application;

import com.wtc.reference.application.port.PayoutCatalogRepository;
import com.wtc.reference.application.port.PayoutCatalogRepository.OperatorRef;
import com.wtc.reference.application.port.PayoutCatalogRepository.SyncCountry;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Écritures des référentiels issus du fournisseur (codes payeur, banques, libellés). Appelé par le
 * back-office, qui contrôle les droits ; chaque écriture est courte et indépendante : un import
 * long ne retient aucune transaction pendant ses appels réseau. Rien n'est jamais désactivé ni
 * supprimé automatiquement.
 */
@Service
public class ReferenceImportService {

    private final PayoutCatalogRepository catalog;

    ReferenceImportService(PayoutCatalogRepository catalog) {
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public List<SyncCountry> receivingCountries() {
        return catalog.receivingCountries();
    }

    @Transactional(readOnly = true)
    public List<OperatorRef> operatorsOf(String countryCode) {
        return catalog.operatorsOf(countryCode);
    }

    @Transactional
    public void setBankPayerCode(String countryCode, String payerCode) {
        catalog.setBankPayerCode(countryCode, payerCode);
    }

    @Transactional
    public void setOperatorPayerCode(long operatorId, String payerCode) {
        catalog.setOperatorPayerCode(operatorId, payerCode);
    }

    @Transactional
    public void upsertBank(String countryCode, long waceBankId, String bankCode, String name) {
        catalog.upsertBank(countryCode, waceBankId, bankCode, name);
    }

    @Transactional
    public void upsertReferenceValue(
            String category, String partyTypePair, String code, String label, String waceCode) {
        catalog.upsertReferenceValue(category, partyTypePair, code, label, waceCode);
    }
}
