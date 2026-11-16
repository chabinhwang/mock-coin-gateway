package com.mockcoin.gateway.global.security;

import java.time.Clock;
import java.time.Instant;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mockcoin.gateway.global.config.GatewayAuthProperties;
import com.mockcoin.gateway.global.error.GatewayErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// Authorization 헤더에서 mock-coin userId를 해석한다 (로컬 HS512 검증 + Caffeine 캐시 + /auth/me fallback)
// 1) 로컬 검증과 공유 Redis denylist가 켜져 있으면 토큰과 jti를 검증한다.
// 2) 아니면 같은 공유 denylist가 켜져 있을 때만 짧은 TTL의 긍정 캐시를 보고, hit마다 폐기 여부를 다시 확인한다.
// 3) 아니면 user-service /api/v1/auth/me를 authoritative 경로로 호출하고, 성공 응답을 설정 TTL(토큰 exp 상한)만큼 캐시한다.
// user-service를 source of truth로 두면서, 다른 gateway 인스턴스에서 폐기된 토큰을 받아들이지 않고 캐시를 재사용한다.
@Component
public class GatewayTokenAuthenticator {
    public static final String AUTHENTICATED_USER_ID_REQUEST_ATTRIBUTE = "com.mockcoin.gateway.authenticatedUserId";

    private final GatewayAuthProperties authProperties;
    private final GatewayLocalTokenVerifier localTokenVerifier;
    private final GatewayRemoteAuthClient remoteAuthClient;
    private final Cache<String, CachedAuthentication> remoteUserIdCache;
    private final Clock clock;

    // 인증 설정, 로컬 토큰 검증기, 원격 인증 클라이언트를 주입받고 시스템 UTC 시계를 쓴다.
    @Autowired
    public GatewayTokenAuthenticator(
            GatewayAuthProperties authProperties,
            GatewayLocalTokenVerifier localTokenVerifier,
            GatewayRemoteAuthClient remoteAuthClient
    ) {
        this(authProperties, localTokenVerifier, remoteAuthClient, Clock.systemUTC());
    }

    // 원격 인증 결과를 담을 Caffeine 캐시를 설정 TTL·최대 크기(최소 1)로 만든다.
    GatewayTokenAuthenticator(
            GatewayAuthProperties authProperties,
            GatewayLocalTokenVerifier localTokenVerifier,
            GatewayRemoteAuthClient remoteAuthClient,
            Clock clock
    ) {
        this.authProperties = authProperties;
        this.localTokenVerifier = localTokenVerifier;
        this.remoteAuthClient = remoteAuthClient;
        this.clock = clock;
        this.remoteUserIdCache = Caffeine.newBuilder()
                .expireAfterWrite(authProperties.getRemoteCacheTtl())
                .maximumSize(Math.max(1L, authProperties.getRemoteCacheMaxSize()))
                .build();
    }

    // Authorization 헤더에서 userId를 해석하고, 실패 시 401을 던진다
    // 순서: 로컬 검증(denylist 포함) → 만료 전 캐시(denylist 재확인) → user-service /auth/me.
    // clientIp는 신뢰 프록시 기준으로 이미 해석된 값이어야 하며, 원본 forwarding 헤더를 넘기면 안 된다.
    public Long resolveUserId(String authorizationHeader, String clientIp) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw GatewayErrorCode.ACCESS_TOKEN_REJECTED.exception();
        }
        String authorization = normalize(authorizationHeader);

        if (authProperties.isLocalVerifyEnabled() && authProperties.isDenylistRedisEnabled()) {
            GatewayLocalTokenVerifier.VerificationResult localResult = localTokenVerifier.verify(authorization);
            if (localResult.denied()) {
                // 구조가 맞고 만료 전이지만 jti가 공유 denylist에 있는 토큰(로그아웃/강제 폐기)이다. 긍정 캐시나 user-service를
                // 보지 않고 바로 거부해 로컬 검증 fast path가 폐기된 토큰을 받아들이는 일이 없게 한다.
                throw GatewayErrorCode.ACCESS_TOKEN_REJECTED.exception();
            }
            if (localResult.userId() != null) {
                return localResult.userId();
            }
        }

        CachedAuthentication cached = unexpiredCachedAuthentication(authorization);
        if (cached != null) {
            switch (localTokenVerifier.denylistStatus(cached.jwtId())) {
                case DENIED -> {
                    remoteUserIdCache.invalidate(authorization);
                    throw GatewayErrorCode.ACCESS_TOKEN_REJECTED.exception();
                }
                case NOT_DENIED -> {
                    return cached.userId();
                }
                case UNAVAILABLE -> {
                    // 공유 denylist를 확인할 수 없으면 stale cache를 신뢰하지 않고 authoritative path로 간다.
                    remoteUserIdCache.invalidate(authorization);
                }
            }
        }

        Long remote = remoteAuthClient.resolveUserId(authorization, clientIp);
        cacheAffirmativeAnswer(authorization, remote);
        return remote;
    }

    // 캐시된 인증 결과는 토큰 exp 이전까지만 재사용한다 (만료 시 무효화 후 authoritative path로 넘긴다)
    private CachedAuthentication unexpiredCachedAuthentication(String authorization) {
        if (!authProperties.isDenylistRedisEnabled()) {
            return null;
        }
        CachedAuthentication cached = remoteUserIdCache.getIfPresent(authorization);
        if (cached == null) {
            return null;
        }
        if (!cached.expiresAt().isAfter(clock.instant())) {
            remoteUserIdCache.invalidate(authorization);
            return null;
        }
        return cached;
    }

    // 원격 인증 성공 결과를 캐시한다 (jti/exp를 읽을 수 없거나 이미 만료된 토큰은 캐시하지 않는다)
    private void cacheAffirmativeAnswer(String authorization, Long userId) {
        if (!authProperties.isDenylistRedisEnabled()) {
            return;
        }
        GatewayLocalTokenVerifier.CacheableClaims claims = localTokenVerifier.extractCacheableClaims(authorization);
        if (claims == null || !claims.expiresAt().isAfter(clock.instant())) {
            return;
        }
        remoteUserIdCache.put(authorization, new CachedAuthentication(userId, claims.jwtId(), claims.expiresAt()));
    }

    // 로그아웃 이후 이 인스턴스에 캐시된 토큰을 즉시 무효화한다
    // 다른 인스턴스는 캐시 hit 때 공유 denylist를 다시 확인해 같은 로그아웃을 반영한다.
    public void invalidate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return;
        }
        remoteUserIdCache.invalidate(normalize(authorizationHeader));
    }

    // 원격 호출 없이 best-effort로 userId를 해석한다 (실패 시 null 반환)
    // 주문 rate limit처럼 다른 키로 대체해도 되는 호출자가 쓴다.
    public Long resolveUserIdQuietly(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return null;
        }
        String authorization = normalize(authorizationHeader);
        if (authProperties.isLocalVerifyEnabled() && authProperties.isDenylistRedisEnabled()) {
            GatewayLocalTokenVerifier.VerificationResult localResult = localTokenVerifier.verify(authorization);
            if (localResult.denied()) {
                return null;
            }
            if (localResult.userId() != null) {
                return localResult.userId();
            }
        }
        CachedAuthentication cached = unexpiredCachedAuthentication(authorization);
        if (cached == null) {
            return null;
        }
        return switch (localTokenVerifier.denylistStatus(cached.jwtId())) {
            case NOT_DENIED -> cached.userId();
            case DENIED -> {
                remoteUserIdCache.invalidate(authorization);
                yield null;
            }
            case UNAVAILABLE -> null;
        };
    }

    // Bearer 접두사가 없으면 붙여 같은 토큰이 같은 캐시 키가 되게 한다.
    private static String normalize(String header) {
        return header.regionMatches(true, 0, "Bearer ", 0, 7) ? header : "Bearer " + header;
    }

    // 캐시된 인증 결과: jwtId로 로그아웃 무효화를, expiresAt으로 토큰 수명 상한을 확인한다
    private record CachedAuthentication(Long userId, String jwtId, Instant expiresAt) {
    }
}
