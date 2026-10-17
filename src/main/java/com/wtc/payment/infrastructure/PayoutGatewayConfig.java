package com.wtc.payment.infrastructure;

import com.wtc.payment.application.PayoutGateway;
import com.wtc.shared.config.WtcProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Choix explicite du fournisseur : {@code wtc.payment.provider} = {@code wace} ou {@code fake},
 * sinon démarrage refusé.
 */
@Configuration(proxyBeanMethods = false)
class PayoutGatewayConfig {

    @Bean
    @ConditionalOnProperty(prefix = "wtc.payment", name = "provider", havingValue = "fake")
    FakePayoutGateway fakePayoutGateway(
            @org.springframework.beans.factory.annotation.Value(
                            "${wtc.payment.fake-auto-complete-after:}")
                    String autoCompleteAfter) {
        // Développement uniquement : « 20s » fait payer chaque ordre 20 s après sa création. Vide :
        // jamais (tests).
        return autoCompleteAfter.isBlank()
                ? new FakePayoutGateway()
                : new FakePayoutGateway(
                        org.springframework.boot.convert.DurationStyle.detectAndParse(
                                autoCompleteAfter));
    }

    @Bean
    @ConditionalOnProperty(prefix = "wtc.payment", name = "provider", havingValue = "wace")
    WacePayoutGateway wacePayoutGateway(WtcProperties properties, JsonMapper json) {
        return new WacePayoutGateway(
                properties.payment().wace(), json, java.time.Clock.systemUTC());
    }

    @Bean
    @ConditionalOnMissingBean(PayoutGateway.class)
    PayoutGateway noProvider() {
        throw new IllegalStateException(
                "wtc.payment.provider est obligatoire : « wace » (réel) ou « fake » (développement"
                        + " et tests uniquement).");
    }
}
