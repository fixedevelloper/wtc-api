package com.wtc.reference.api;

import com.wtc.reference.application.ReferenceValueView;
import com.wtc.reference.application.ReferenceValuesService;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listes de référence d'un transfert, pour les formulaires des clients (authentification requise).
 */
@RestController
@RequestMapping("/api/reference")
class ReferenceValuesController {

    private final ReferenceValuesService values;

    ReferenceValuesController(ReferenceValuesService values) {
        this.values = values;
    }

    @GetMapping("/transfer-purposes")
    List<ReferenceValueView> purposes(
            @RequestParam(required = false) @Pattern(regexp = "P2P|P2B|B2P|B2B")
                    String partyTypePair) {
        return values.transferPurposes(partyTypePair);
    }

    @GetMapping("/sources-of-funds")
    List<ReferenceValueView> sources(
            @RequestParam(required = false) @Pattern(regexp = "P2P|P2B|B2P|B2B")
                    String partyTypePair) {
        return values.sourcesOfFunds(partyTypePair);
    }

    @GetMapping("/relationships")
    List<ReferenceValueView> relationships(
            @RequestParam(required = false) @Pattern(regexp = "P2P|P2B|B2P|B2B")
                    String partyTypePair) {
        return values.relationships(partyTypePair);
    }
}
