package com.wtc.payment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.wtc.payment.application.PayoutGateway;
import com.wtc.shared.config.WtcProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.json.JsonMapper;

/**
 * Le fournisseur de paiement se choisit explicitement : jamais de faux fournisseur par omission.
 */
class PayoutGatewayConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WtcProperties.class)
    @Import(PayoutGatewayConfig.class)
    static class Context {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(Context.class);

    @Test
    void refusesToStartWithoutAnExplicitProvider() {
        runner.run(
                context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "wtc.payment.provider est obligatoire : « wace » (réel) ou «"
                                            + " fake » (développement et tests uniquement).");
                });
    }

    @Test
    void anUnknownProviderIsRefusedToo() {
        runner.withPropertyValues("wtc.payment.provider=stripe")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void theFakeProviderIsAvailableOnRequest() {
        runner.withPropertyValues("wtc.payment.provider=fake")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            assertThat(context.getBean(PayoutGateway.class))
                                    .isInstanceOf(FakePayoutGateway.class);
                        });
    }

    @Test
    void theRealProviderNeedsItsCredentials() {
        runner.withPropertyValues("wtc.payment.provider=wace")
                .run(
                        context -> {
                            assertThat(context).hasFailed();
                            assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage(
                                            "wtc.payment.wace.base-url, email et password sont"
                                                + " obligatoires avec le fournisseur « wace ».");
                        });
        runner.withPropertyValues(
                        "wtc.payment.provider=wace",
                        "wtc.payment.wace.base-url=http://localhost:1",
                        "wtc.payment.wace.email=a@b.cd",
                        "wtc.payment.wace.password=x")
                .run(
                        context ->
                                assertThat(context.getBean(PayoutGateway.class))
                                        .isInstanceOf(WacePayoutGateway.class));
    }
}
