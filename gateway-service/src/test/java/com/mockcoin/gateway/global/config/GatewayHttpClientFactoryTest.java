package com.mockcoin.gateway.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@SpringBootTest
class GatewayHttpClientFactoryTest {
    private static final Duration IAP_GRANT_NOT_FOUND_RETRY_WINDOW = Duration.ofMillis(6500);

    @Autowired
    private RestClient restClient;

    @Autowired
    private GatewayHttpProperties properties;

    @Test
    void restClientUsesJdkClientHttpRequestFactoryForConnectionPooling() throws Exception {
        // Reach into RestClient to ensure the underlying request factory is the pooled JDK HttpClient,
        // not the URLConnection-backed SimpleClientHttpRequestFactory which lacks reuse across calls.
        Field requestFactoryField = restClient.getClass().getDeclaredField("clientRequestFactory");
        requestFactoryField.setAccessible(true);
        Object factory = requestFactoryField.get(restClient);

        assertThat(factory).isInstanceOf(ClientHttpRequestFactory.class);
        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
    }

    @Test
    void defaultReadTimeoutCoversIapGrantNotFoundRetryWindow() {
        assertThat(properties.readTimeout()).isGreaterThan(IAP_GRANT_NOT_FOUND_RETRY_WINDOW);
    }
}
