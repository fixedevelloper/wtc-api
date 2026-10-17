package com.wtc.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.support.OpenApiContract;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tout code d'erreur émis par l'application doit être documenté dans le contrat (liste {@code
 * x-known-codes}).
 */
class ErrorCodeContractTest {

    @Test
    void everyApplicationErrorCodeIsDocumentedInTheContract() {
        List<String> documented = OpenApiContract.knownErrorCodes();
        List<String> missing =
                Arrays.stream(ErrorCode.values())
                        .map(Enum::name)
                        .filter(code -> !documented.contains(code))
                        .toList();

        assertThat(documented).isNotEmpty();
        assertThat(missing)
                .as("codes d'erreur absents de api/openapi.yaml (Problem.code.x-known-codes)")
                .isEmpty();
    }
}
