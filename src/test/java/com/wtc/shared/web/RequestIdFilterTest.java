package com.wtc.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String incoming, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (incoming != null) {
            request.addHeader(RequestIdFilter.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(
                request,
                response,
                new MockFilterChain() {
                    @Override
                    public void doFilter(
                            jakarta.servlet.ServletRequest req,
                            jakarta.servlet.ServletResponse res) {
                        seenInChain.set(RequestIdFilter.current());
                    }
                });
        return response.getHeader(RequestIdFilter.HEADER);
    }

    @Test
    void keepsAValidIncomingId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        assertThat(run("client-req-12345", seen)).isEqualTo("client-req-12345");
        assertThat(seen.get()).isEqualTo("client-req-12345");
    }

    @Test
    void replacesAnInvalidOrMissingId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        assertThat(run("x", seen)).isNotEqualTo("x").hasSize(36);
        assertThat(run("<script>alert(1)</script>", seen)).doesNotContain("<");
        assertThat(run(null, seen)).hasSize(36);
    }

    @Test
    void clearsTheMdcAfterTheRequest() throws Exception {
        run(null, new AtomicReference<>());
        assertThat(RequestIdFilter.current()).isNull();
    }
}
