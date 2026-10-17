package com.wtc.shared.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.wtc.shared.error.RateLimitedException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Seaux à jetons Bucket4j en mémoire, expirés après inactivité (taille bornée : pas de fuite
 * mémoire par clés aléatoires).
 */
@Component
class Bucket4jRateLimiter implements RateLimiter {

    private final Cache<String, Bucket> buckets =
            Caffeine.newBuilder()
                    .maximumSize(200_000)
                    .expireAfterAccess(Duration.ofHours(2))
                    .build();

    @Override
    public void check(String key, Limit limit) {
        // La clé inclut le plafond : deux politiques différentes sur la même clé ne partagent pas
        // leur seau.
        String bucketKey = key + "|" + limit.capacity() + "/" + limit.window();
        Bucket bucket =
                buckets.get(
                        bucketKey,
                        k ->
                                Bucket.builder()
                                        .addLimit(
                                                Bandwidth.builder()
                                                        .capacity(limit.capacity())
                                                        .refillIntervally(
                                                                limit.capacity(), limit.window())
                                                        .build())
                                        .build());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throw new RateLimitedException(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
    }
}
