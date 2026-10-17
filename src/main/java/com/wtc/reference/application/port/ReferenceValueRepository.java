package com.wtc.reference.application.port;

import com.wtc.reference.application.ReferenceValueView;
import java.util.List;

public interface ReferenceValueRepository {

    /** Valeurs actives d'une catégorie valables pour un type de transfert ({@code ALL} inclus). */
    List<ReferenceValueView> list(String category, String partyTypePair);
}
