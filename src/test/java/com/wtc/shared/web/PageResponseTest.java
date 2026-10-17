package com.wtc.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void mapsASpringPageToTheContractEnvelope() {
        var page = new PageImpl<>(List.of(1, 2), PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.of(page, n -> "n" + n);

        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(2);
        assertThat(response.totalElements()).isEqualTo(5);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.content()).containsExactly("n1", "n2");
    }

    @Test
    void contentIsAnImmutableCopy() {
        List<String> source = new ArrayList<>(List.of("a"));
        PageResponse<String> response = new PageResponse<>(0, 20, 1, 1, source);
        source.add("b");

        assertThat(response.content()).containsExactly("a");
        assertThatThrownBy(() -> response.content().add("c"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
