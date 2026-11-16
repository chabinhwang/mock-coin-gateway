package com.mockcoin.gateway.global.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// gateway 측 JWT 로컬 검증/원격 검증 캐시 설정. local-verify-enabled와 denylist-redis-enabled가 모두 true면 user-service와 같은
// 공유 secret(mockcoin.auth.jwt-secret)으로 HS512 서명을 best-effort로 로컬 검증하고, 실패(서명 불일치·만료·클레임 오류·secret/공유 denylist 부재 등) 시 authoritative /api/v1/auth/me로 넘어간다.
// 긍정 Caffeine 캐시는 공유 Redis denylist가 있을 때만 켜지며, hit마다 jti를 denylist로 확인해 로그아웃 폐기가 gateway 인스턴스 간에 보인다.
@ConfigurationProperties(prefix = "mockcoin.gateway.auth")
public class GatewayAuthProperties {
    private boolean localVerifyEnabled = false;
    private String jwtSecret = "";
    private Duration remoteCacheTtl = Duration.ofSeconds(30);
    private long remoteCacheMaxSize = 10_000L;
    // user-service가 같은 Redis denylist를 사용할 때만 affirmative cache/local verify를 허용한다.
    private boolean denylistRedisEnabled = false;
    // user-service denylist 키 프리픽스와 일치시켜 로컬검증 경로에서 jti 무효화를 조회한다.
    // user-service `mockcoin.auth.denylist.key-prefix` 기본값(AuthDenylistProperties)과 동일해야 한다.
    private String denylistKeyPrefix = "token:blacklist:";

    public boolean isLocalVerifyEnabled() {
        return localVerifyEnabled;
    }

    public void setLocalVerifyEnabled(boolean localVerifyEnabled) {
        this.localVerifyEnabled = localVerifyEnabled;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getRemoteCacheTtl() {
        return remoteCacheTtl;
    }

    public void setRemoteCacheTtl(Duration remoteCacheTtl) {
        this.remoteCacheTtl = remoteCacheTtl;
    }

    public long getRemoteCacheMaxSize() {
        return remoteCacheMaxSize;
    }

    public void setRemoteCacheMaxSize(long remoteCacheMaxSize) {
        this.remoteCacheMaxSize = remoteCacheMaxSize;
    }

    public boolean isDenylistRedisEnabled() {
        return denylistRedisEnabled;
    }

    public void setDenylistRedisEnabled(boolean denylistRedisEnabled) {
        this.denylistRedisEnabled = denylistRedisEnabled;
    }

    public String getDenylistKeyPrefix() {
        return denylistKeyPrefix;
    }

    public void setDenylistKeyPrefix(String denylistKeyPrefix) {
        this.denylistKeyPrefix = denylistKeyPrefix;
    }
}
