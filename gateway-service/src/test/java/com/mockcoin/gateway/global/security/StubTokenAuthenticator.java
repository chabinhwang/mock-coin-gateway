package com.mockcoin.gateway.global.security;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.config.GatewayAuthProperties;
import com.mockcoin.gateway.global.config.ServiceRouteProperties;
import com.mockcoin.shared.api.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

/**
 * Test helper that resolves user ids from a fixed map. Used to verify rate-limit key derivation without
 * spinning up a stub auth backend.
 */
final class StubTokenAuthenticator extends GatewayTokenAuthenticator {
    private final Map<String, Long> mapping = new HashMap<>();

    StubTokenAuthenticator() {
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

    void put(String authorizationHeader, Long userId) {
        mapping.put(authorizationHeader, userId);
    }

    @Override
    public Long resolveUserId(String authorizationHeader, String clientIp) {
        Long mapped = mapping.get(authorizationHeader);
        if (mapped == null) {
            throw new ApiException(401, "UNAUTHORIZED", "not registered");
        }
        return mapped;
    }

    @Override
    public Long resolveUserIdQuietly(String authorizationHeader) {
        return mapping.get(authorizationHeader);
    }
}
