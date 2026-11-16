package com.mockcoin.gateway.admin.integration;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.admin.config.GatewayAdminRedisProperties;
import com.mockcoin.gateway.admin.integration.AdminCredentialStore;
import com.mockcoin.gateway.admin.integration.AdminLockoutStore;
import com.mockcoin.gateway.admin.integration.AdminSessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

// mockcoin.gateway.admin.redis-enabled=true이고 RedisConnectionFactory가 있으면 admin 세션/잠금/자격증명/challenge 저장소를 Redis 어댑터로 교체한다
// 빈 이름(redisAdminSessionStore 등)이 in-memory 어댑터의 @ConditionalOnMissingBean(name = ...) 조건과 맞물려 구현이 깔끔하게 바뀐다.
@Configuration
@ConditionalOnProperty(prefix = "mockcoin.gateway.admin", name = "redis-enabled", havingValue = "true")
@ConditionalOnBean(RedisConnectionFactory.class)
public class RedisAdminStoreConfig {

    // Redis 기반 admin 세션 저장소 빈을 등록한다
    @Bean(name = "redisAdminSessionStore")
    AdminSessionStore redisAdminSessionStore(
            StringRedisTemplate redisTemplate,
            GatewayAdminRedisProperties properties) {
        return new RedisAdminSessionStore(redisTemplate, properties);
    }

    // Redis 기반 admin 잠금 상태 저장소 빈을 등록한다
    @Bean(name = "redisAdminLockoutStore")
    AdminLockoutStore redisAdminLockoutStore(
            StringRedisTemplate redisTemplate,
            GatewayAdminRedisProperties properties) {
        return new RedisAdminLockoutStore(redisTemplate, properties);
    }

    // Redis 기반 admin WebAuthn 자격증명 저장소 빈을 등록한다
    @Bean(name = "redisAdminCredentialStore")
    AdminCredentialStore redisAdminCredentialStore(
            StringRedisTemplate redisTemplate,
            GatewayAdminRedisProperties properties,
            ObjectMapper objectMapper) {
        return new RedisAdminCredentialStore(redisTemplate, properties, objectMapper);
    }

    // Redis 기반 admin WebAuthn challenge 저장소 빈을 등록한다
    @Bean(name = "redisAdminWebAuthnChallengeStore")
    AdminWebAuthnChallengeStore redisAdminWebAuthnChallengeStore(
            StringRedisTemplate redisTemplate,
            GatewayAdminRedisProperties properties) {
        return new RedisAdminWebAuthnChallengeStore(redisTemplate, properties);
    }
}
