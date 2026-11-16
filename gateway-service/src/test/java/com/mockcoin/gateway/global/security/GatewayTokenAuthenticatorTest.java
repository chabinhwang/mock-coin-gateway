package com.mockcoin.gateway.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.config.GatewayAuthProperties;
import com.mockcoin.gateway.global.config.ServiceRouteProperties;
import com.mockcoin.shared.api.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

class GatewayTokenAuthenticatorTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private AtomicInteger callCount;
    private AtomicReference<String> lastForwardedFor;
    private AtomicBoolean rejectDynamicToken;

    @BeforeEach
    void setUp() throws IOException {
        callCount = new AtomicInteger();
        lastForwardedFor = new AtomicReference<>();
        rejectDynamicToken = new AtomicBoolean();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/v1/auth/me", exchange -> {
            callCount.incrementAndGet();
            lastForwardedFor.set(exchange.getRequestHeaders().getFirst("X-Forwarded-For"));
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (rejectDynamicToken.get() && authorization != null && authorization.startsWith("Bearer ey")) {
                writeResponse(exchange, 401, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"UNAUTHORIZED\"}}");
                return;
            }
            if ("Bearer invalid-remote".equals(authorization)) {
                writeResponse(exchange, 401, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"UNAUTHORIZED\"}}");
                return;
            }
            if ("Bearer forbidden-remote".equals(authorization)) {
                writeResponse(exchange, 403, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"FORBIDDEN\"}}");
                return;
            }
            if ("Bearer throttled-remote".equals(authorization)) {
                writeResponse(exchange, 429, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"RATE_LIMIT_EXCEEDED\"}}");
                return;
            }
            if ("Bearer unavailable-remote".equals(authorization)) {
                writeResponse(exchange, 503, "{\"resultType\":\"FAIL\",\"success\":null,\"error\":{\"errorCode\":\"SERVICE_UNAVAILABLE\"}}");
                return;
            }
            if ("Bearer malformed-remote".equals(authorization)) {
                writeResponse(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{},\"error\":null}");
                return;
            }
            if ("Bearer empty-remote".equals(authorization)) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            writeResponse(exchange, 200, "{\"resultType\":\"SUCCESS\",\"success\":{\"userId\":777},\"error\":null}");
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void localVerifySkipsRemoteCallForValidAccessToken() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        Long userId = authenticator.resolveUserId("Bearer " + token, null);

        assertThat(userId).isEqualTo(1234L);
        assertThat(callCount.get()).isZero();
    }

    @Test
    void localVerifyFallsBackToRemoteWhenSignatureMismatches() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken("a-different-secret-that-mismatches", 1234L, Duration.ofMinutes(5));

        Long userId = authenticator.resolveUserId("Bearer " + token, null);

        // Remote stub server returns userId=777 for any token, so a local mismatch must fall through.
        assertThat(userId).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void localVerifyRejectsExpiredAccessToken() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(-5));

        Long userId = authenticator.resolveUserId("Bearer " + token, null);

        // Expired local token should fall back to remote, where the stub answers 777.
        assertThat(userId).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void localVerifyRejectsRefreshTokenType() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signToken(SECRET, "refresh", null, Duration.ofMinutes(5));

        Long userId = authenticator.resolveUserId("Bearer " + token, null);

        // Non-access token should not be honored locally; remote stub returns 777.
        assertThat(userId).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void localVerifyDisabledAlwaysCallsRemote() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(false);
        properties.setJwtSecret(SECRET);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        Long userId = authenticator.resolveUserId("Bearer " + token, null);

        assertThat(userId).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void remoteResultIsCachedForRepeatedCalls() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(false);
        properties.setRemoteCacheTtl(Duration.ofMinutes(1));
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        authenticator.resolveUserId("Bearer " + token, null);
        authenticator.resolveUserId("Bearer " + token, null);
        authenticator.resolveUserId("Bearer " + token, null);

        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void sharedDenylistDisabledDoesNotCacheRemoteAffirmativeResponse() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setRemoteCacheTtl(Duration.ofMinutes(1));
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);
        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        authenticator.resolveUserId("Bearer " + token, null);
        authenticator.resolveUserId("Bearer " + token, null);

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void localVerificationRequiresConfiguredSharedDenylist() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void sharedDenylistRevocationRejectsRemoteCacheHitFromAnotherGatewayInstance() {
        AtomicBoolean denied = new AtomicBoolean();
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setRemoteCacheTtl(Duration.ofMinutes(1));
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties, denylist(key -> denied.get()));
        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        denied.set(true);

        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer " + token, null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.statusCode()).isEqualTo(401));
        assertThat(authenticator.resolveUserIdQuietly("Bearer " + token)).isNull();
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void unavailableSharedDenylistBypassesRemoteAffirmativeCache() {
        AtomicBoolean unavailable = new AtomicBoolean();
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setRemoteCacheTtl(Duration.ofMinutes(1));
        properties.setDenylistRedisEnabled(true);
        StringRedisTemplate denylist = new StringRedisTemplate() {
            @Override
            public Boolean hasKey(String key) {
                if (unavailable.get()) {
                    throw new IllegalStateException("redis unavailable");
                }
                return false;
            }
        };
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties, denylist);
        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        unavailable.set(true);
        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);

        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void denylistFailureInvalidatesCachedAffirmativeBeforeRemoteRejection() {
        AtomicInteger denylistMode = new AtomicInteger();
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setRemoteCacheTtl(Duration.ofMinutes(1));
        properties.setDenylistRedisEnabled(true);
        StringRedisTemplate denylist = new StringRedisTemplate() {
            @Override
            public Boolean hasKey(String key) {
                if (denylistMode.get() == 1) {
                    throw new IllegalStateException("redis unavailable");
                }
                return false;
            }
        };
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties, denylist);
        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        rejectDynamicToken.set(true);
        denylistMode.set(1);
        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer " + token, null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.statusCode()).isEqualTo(401));

        denylistMode.set(2);
        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer " + token, null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.statusCode()).isEqualTo(401));
        assertThat(callCount.get()).isEqualTo(3);
    }

    @Test
    void blankAuthorizationFailsLoudly() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        assertThatThrownBy(() -> authenticator.resolveUserId("", null))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void resolveQuietlyReturnsNullForUnknownTokenWithoutLocalVerify() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(false);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        Long userId = authenticator.resolveUserIdQuietly("Bearer abc");

        assertThat(userId).isNull();
        assertThat(callCount.get()).isZero();
    }

    @Test
    void resolveQuietlyReturnsCachedUserIdAfterRemoteVerify() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(false);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));
        authenticator.resolveUserId("Bearer " + token, null);

        Long quiet = authenticator.resolveUserIdQuietly("Bearer " + token);

        assertThat(quiet).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void resolveQuietlyUsesLocalVerifyWhenEnabled() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        Long quiet = authenticator.resolveUserIdQuietly("Bearer " + token);

        assertThat(quiet).isEqualTo(1234L);
        assertThat(callCount.get()).isZero();
    }

    @Test
    void localVerifyRejectsDenylistedAccessToken() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties, denylist(key -> true));

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        // A denylisted (logged-out) token must be rejected locally without falling back to remote.
        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer " + token, null))
                .isInstanceOf(ApiException.class);
        assertThat(authenticator.resolveUserIdQuietly("Bearer " + token)).isNull();
        assertThat(callCount.get()).isZero();
    }

    @Test
    void localVerifyFallsBackToRemoteWhenDenylistUnavailable() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setLocalVerifyEnabled(true);
        properties.setJwtSecret(SECRET);
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties, unavailableDenylist());

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(5));

        Long userId = authenticator.resolveUserId("Bearer " + token, null);

        // Denylist store down: local verify must not accept the token; it falls back to /auth/me (777).
        assertThat(userId).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);
    }

    @Test
    void expiredTokenIsNeverCachedAfterRemoteVerification() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setDenylistRedisEnabled(true);
        GatewayTokenAuthenticator authenticator = newAuthenticator(properties);

        // Already past exp. user-service stays the authority, so every call must reach it again
        // instead of being answered from a cache entry that could never expire on its own.
        String token = signAccessToken(SECRET, 1234L, Duration.ofSeconds(-30));

        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);

        assertThat(callCount.get()).isEqualTo(2);
        assertThat(authenticator.resolveUserIdQuietly("Bearer " + token)).isNull();
    }

    @Test
    void cachedAuthenticationStopsBeingReusedOnceTheTokenExpires() {
        GatewayAuthProperties properties = new GatewayAuthProperties();
        properties.setDenylistRedisEnabled(true);
        properties.setRemoteCacheTtl(Duration.ofMinutes(10));
        MutableClock clock = new MutableClock(Instant.now());
        GatewayTokenAuthenticator authenticator = newAuthenticator(
                properties,
                denylist(key -> false),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                clock
        );

        String token = signAccessToken(SECRET, 1234L, Duration.ofMinutes(1));

        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        // Second call inside the token's lifetime is served from cache.
        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(1);

        // The cache TTL still has 9 minutes left, but the token itself is now expired.
        clock.advance(Duration.ofMinutes(2));

        assertThat(authenticator.resolveUserIdQuietly("Bearer " + token)).isNull();
        assertThat(authenticator.resolveUserId("Bearer " + token, null)).isEqualTo(777L);
        assertThat(callCount.get()).isEqualTo(2);
    }

    @Test
    void remoteUnauthorizedRemainsUnauthorized() {
        GatewayTokenAuthenticator authenticator = newAuthenticator(new GatewayAuthProperties());

        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer invalid-remote", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(401);
                    assertThat(exception.errorCode()).isEqualTo("UNAUTHORIZED");
                });
        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer forbidden-remote", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(401);
                    assertThat(exception.errorCode()).isEqualTo("UNAUTHORIZED");
                });
    }

    @Test
    void remoteRateLimitAndServiceFailureBecomeUnavailableAndAreNotCached() {
        GatewayTokenAuthenticator authenticator = newAuthenticator(new GatewayAuthProperties());

        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer throttled-remote", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(503);
                    assertThat(exception.errorCode()).isEqualTo("AUTH_SERVICE_UNAVAILABLE");
                });
        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer unavailable-remote", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(503);
                    assertThat(exception.errorCode()).isEqualTo("AUTH_SERVICE_UNAVAILABLE");
                });
        int callsAfterFirstUnavailableResponse = callCount.get();
        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer unavailable-remote", null))
                .isInstanceOf(ApiException.class);

        assertThat(callCount.get()).isGreaterThan(callsAfterFirstUnavailableResponse);
    }

    @Test
    void malformedSuccessfulRemoteResponseIsBadGateway() {
        GatewayTokenAuthenticator authenticator = newAuthenticator(new GatewayAuthProperties());

        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer malformed-remote", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(502);
                    assertThat(exception.errorCode()).isEqualTo("AUTH_SERVICE_INVALID_RESPONSE");
                });
        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer empty-remote", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(502);
                    assertThat(exception.errorCode()).isEqualTo("AUTH_SERVICE_INVALID_RESPONSE");
                });
    }

    @Test
    void remoteTransportFailureBecomesUnavailable() {
        GatewayTokenAuthenticator authenticator = newAuthenticator(
                new GatewayAuthProperties(),
                denylist(key -> false),
                URI.create("http://127.0.0.1:1")
        );

        assertThatThrownBy(() -> authenticator.resolveUserId("Bearer transport-failure", null))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.statusCode()).isEqualTo(503);
                    assertThat(exception.errorCode()).isEqualTo("AUTH_SERVICE_UNAVAILABLE");
                });
    }

    @Test
    void remoteVerificationForwardsResolvedClientIp() {
        GatewayTokenAuthenticator authenticator = newAuthenticator(new GatewayAuthProperties());

        Long userId = authenticator.resolveUserId("Bearer client-ip-token", "203.0.113.44");

        assertThat(userId).isEqualTo(777L);
        assertThat(lastForwardedFor.get()).isEqualTo("203.0.113.44");
    }

    private GatewayTokenAuthenticator newAuthenticator(GatewayAuthProperties properties) {
        // Default to a reachable denylist that denies nothing, so local verification can complete its
        // fast path. Tests that exercise revocation/unavailability pass a specific denylist instead.
        return newAuthenticator(properties, denylist(key -> false));
    }

    private GatewayTokenAuthenticator newAuthenticator(GatewayAuthProperties properties, StringRedisTemplate denylistRedisTemplate) {
        return newAuthenticator(
                properties,
                denylistRedisTemplate,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort())
        );
    }

    private GatewayTokenAuthenticator newAuthenticator(
            GatewayAuthProperties properties,
            StringRedisTemplate denylistRedisTemplate,
            URI userServiceUri
    ) {
        return newAuthenticator(properties, denylistRedisTemplate, userServiceUri, Clock.systemUTC());
    }

    private GatewayTokenAuthenticator newAuthenticator(
            GatewayAuthProperties properties,
            StringRedisTemplate denylistRedisTemplate,
            URI userServiceUri,
            Clock clock
    ) {
        ServiceRouteProperties routes = new ServiceRouteProperties(
                userServiceUri,
                URI.create("http://trading.invalid"),
                URI.create("http://market.invalid"),
                URI.create("http://ingestor.invalid"),
                URI.create("http://community.invalid")
        );
        GatewayLocalTokenVerifier localTokenVerifier = new GatewayLocalTokenVerifier(
                OBJECT_MAPPER,
                properties,
                denylistRedisTemplate
        );
        GatewayRemoteAuthClient remoteAuthClient = new GatewayRemoteAuthClient(
                routes,
                RestClient.builder().build(),
                OBJECT_MAPPER
        );
        return new GatewayTokenAuthenticator(properties, localTokenVerifier, remoteAuthClient, clock);
    }

    // 캐시 재사용 여부를 토큰 exp 기준으로 검증하기 위해 테스트에서 시간을 앞당긴다
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static StringRedisTemplate denylist(Predicate<String> denied) {
        return new StringRedisTemplate() {
            @Override
            public Boolean hasKey(String key) {
                return denied.test(key);
            }
        };
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static StringRedisTemplate unavailableDenylist() {
        return new StringRedisTemplate() {
            @Override
            public Boolean hasKey(String key) {
                throw new IllegalStateException("redis unavailable");
            }
        };
    }

    private static String signAccessToken(String secret, Long userId, Duration ttl) {
        return signToken(secret, "access", userId, ttl);
    }

    private static String signToken(String secret, String type, Long userId, Duration ttl) {
        try {
            Map<String, Object> header = Map.of("alg", "HS512", "typ", "JWT");
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("typ", type);
            payload.put("sub", "tossUserKey");
            payload.put("uid", userId);
            payload.put("nickname", "tester");
            payload.put("role", "USER");
            payload.put("sid", null);
            Instant now = Instant.now();
            payload.put("iat", now.getEpochSecond());
            payload.put("exp", now.plus(ttl).getEpochSecond());
            payload.put("jti", UUID.randomUUID().toString());
            String encodedHeader = base64Url(OBJECT_MAPPER.writeValueAsBytes(header));
            String encodedPayload = base64Url(OBJECT_MAPPER.writeValueAsBytes(payload));
            String signedPayload = encodedHeader + "." + encodedPayload;
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String signature = base64Url(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            return signedPayload + "." + signature;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
