package com.wtc.shared.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wtc.shared.error.ErrorCode;
import com.wtc.shared.error.RateLimitedException;
import com.wtc.shared.ratelimit.RateLimiter.Limit;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class Bucket4jRateLimiterTest {

    private final Bucket4jRateLimiter limiter = new Bucket4jRateLimiter();
    private final Limit twoPerMinute = new Limit(2, Duration.ofMinutes(1));

    @Test
    void allowsUpToTheCapacityThenRefusesWithARetryDelay() {
        limiter.check("k", twoPerMinute);
        limiter.check("k", twoPerMinute);

        assertThatThrownBy(() -> limiter.check("k", twoPerMinute))
                .isInstanceOfSatisfying(
                        RateLimitedException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED);
                            assertThat(e.retryAfterSeconds()).isBetween(1L, 60L);
                        });
    }

    @Test
    void keysAreIndependent() {
        limiter.check("a", twoPerMinute);
        limiter.check("a", twoPerMinute);
        assertThatCode(() -> limiter.check("b", twoPerMinute)).doesNotThrowAnyException();
    }

    @Test
    void differentPoliciesOnTheSameKeyDoNotShareTheirBucket() {
        limiter.check("k", twoPerMinute);
        limiter.check("k", twoPerMinute);
        assertThatCode(() -> limiter.check("k", new Limit(100, Duration.ofHours(1))))
                .doesNotThrowAnyException();
    }
}
