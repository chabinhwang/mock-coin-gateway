package com.mockcoin.gateway.global.security;

import java.net.URI;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.config.GatewayAuthProperties;
import com.mockcoin.gateway.global.config.ServiceRouteProperties;
import com.mockcoin.shared.api.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

/**
 * Test helper that always answers "no user id available". Used to satisfy the
 * {@link GatewayTokenAuthenticator} dependency in unit tests where the rate limiter or related
 * filter is not exercising authenticated paths.
 */
final class NullTokenAuthenticator extends GatewayTokenAuthenticator {
    static final NullTokenAuthenticator INSTANCE = new NullTokenAuthenticator();

    private NullTokenAuthenticator() {
        super(
                new GatewayAuthProperties(),
                new GatewayLocalTokenVerifier(
                        new ObjectMapper(),
                        new GatewayAuthProperties(),
                        (StringRedisTemplate) null
                ),
                new GatewayRemoteAuthClient(
                        new ServiceRouteProperties(
                                URI.create("http://user.invalid"),
                                URI.create("http://trading.invalid"),
                                URI.create("http://market.invalid"),
                                URI.create("http://ingestor.invalid"),
                                URI.create("http://community.invalid")
                        ),
                        RestClient.builder().build(),
                        new ObjectMapper()
                )
        );
    }

    @Override
    public Long resolveUserId(String authorizationHeader, String clientIp) {
        throw new ApiException(401, "UNAUTHORIZED", "not used in tests");
    }

    @Override
    public Long resolveUserIdQuietly(String authorizationHeader) {
        return null;
    }
}
