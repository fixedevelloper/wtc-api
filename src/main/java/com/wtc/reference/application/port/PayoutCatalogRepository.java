package com.wtc.reference.application.port;

import com.wtc.reference.domain.Bank;
import com.wtc.reference.domain.MobileOperator;
import java.util.List;
import java.util.Optional;

public interface PayoutCatalogRepository {

    List<Bank> findEnabledBanks(String countryCode);

    List<MobileOperator> findEnabledOperators(String countryCode);

    Optional<Bank> findEnabledBank(long id);

    Optional<MobileOperator> findEnabledOperator(long id);

    // --- import depuis le fournisseur

    record SyncCountry(String code, String currency) {}

    record OperatorRef(long id, String shortCode, String name) {}

    /** Pays actifs pouvant recevoir des paiements. */
    java.util.List<SyncCountry> receivingCountries();

    java.util.List<OperatorRef> operatorsOf(String countryCode);

    void setBankPayerCode(String countryCode, String payerCode);

    void setOperatorPayerCode(long operatorId, String payerCode);

    /**
     * Ajoute ou met à jour une banque (clé : pays + identifiant Wace). N'active ni ne désactive
     * jamais.
     */
    void upsertBank(String countryCode, long waceBankId, String bankCode, String name);

    /**
     * Ajoute ou met à jour une valeur de référence (libellé et valeur Wace). N'active ni ne
     * désactive jamais.
     */
    void upsertReferenceValue(
            String category, String partyTypePair, String code, String label, String waceCode);

    /** Identifiant de la banque chez Wace (numérique) et code de banque, s'ils sont renseignés. */
    Optional<String> waceBankId(long bankId);

    /** Code « payeur » Wace d'un opérateur mobile. */
    Optional<String> operatorPayerCode(long operatorId);

    /** Code « payeur » Wace des envois bancaires d'un pays. */
    Optional<String> bankPayerCode(String countryCode);

    /**
     * Code Wace (libellé attendu par Wace) d'une valeur de référence ; la valeur propre au type de
     * transfert prime sur {@code ALL}.
     */
    Optional<String> waceReferenceCode(String category, String partyTypePair, String code);
}
