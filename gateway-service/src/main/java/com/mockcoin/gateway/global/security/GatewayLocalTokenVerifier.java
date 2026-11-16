package com.mockcoin.gateway.global.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.mockcoin.gateway.global.config.GatewayAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// 공유 secret으로 HS512 access token을 로컬 검증하고 user-service의 Redis denylist로 jti 폐기 여부를 확인한다
@Component
public class GatewayLocalTokenVerifier {
    private static final Logger log = LoggerFactory.getLogger(GatewayLocalTokenVerifier.class);
    private static final String HMAC_ALGORITHM = "HmacSHA512";
    private static final String JWT_ALGORITHM = "HS512";
    private static final String JWT_TYPE = "JWT";
    static final int MIN_SECRET_BYTES = 64;

    private final ObjectMapper objectMapper;
    private final GatewayAuthProperties authProperties;
    private final StringRedisTemplate denylistRedisTemplate;

    // denylist용 Redis 템플릿은 gateway Redis가 설정된 경우에만 있고, 없으면 로컬 검증 대신 원격 인증으로 넘어간다.
    @Autowired
    public GatewayLocalTokenVerifier(
            ObjectMapper objectMapper,
            GatewayAuthProperties authProperties,
            ObjectProvider<StringRedisTemplate> denylistRedisTemplateProvider
    ) {
        this(objectMapper, authProperties, denylistRedisTemplateProvider.getIfAvailable());
    }

    // ObjectMapper, 인증 설정, denylist Redis 템플릿(없으면 null)을 받는다.
    GatewayLocalTokenVerifier(
            ObjectMapper objectMapper,
            GatewayAuthProperties authProperties,
            StringRedisTemplate denylistRedisTemplate
    ) {
        this.objectMapper = objectMapper;
        this.authProperties = authProperties;
        this.denylistRedisTemplate = denylistRedisTemplate;
    }

    // 공유 secret으로 HS512 서명·헤더(alg/typ)·exp·typ=access·uid를 검증한 뒤 jti denylist를 확인한다.
    // 검증할 수 없으면(secret 없음·64바이트 미만, 형식 오류, denylist 조회 불가) UNVERIFIED, 폐기된 토큰이면 revoked를 반환한다.
    VerificationResult verify(String authorization) {
        String secret = authProperties.getJwtSecret();
        if (secret == null || secret.isBlank()) {
            return VerificationResult.UNVERIFIED;
        }
        // user-service와 같이 공유 secret이 너무 짧으면 로컬 검증을 거부하고 경고를 남긴다. 조용히 원격 호출로만 넘어가면
        // user-service가 발급을 거부하는 동안 설정 오류가 계속 가려진다.
        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            log.warn("Gateway JWT secret is shorter than {} bytes; rejecting local HS512 verification", MIN_SECRET_BYTES);
            return VerificationResult.UNVERIFIED;
        }
        String token = authorization.substring(7);
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return VerificationResult.UNVERIFIED;
            }
            // 발급자의 HS512+JWT 헤더가 아니면 거부한다. 헤더에 더 약한 알고리즘이나 none을 적은 위조 토큰이
            // 바이트가 우연히 HS512 키로 검증된다는 이유만으로 통과하는 것을 막는다(alg-confusion 방어).
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!JWT_ALGORITHM.equals(header.path("alg").asText())
                    || !JWT_TYPE.equals(header.path("typ").asText())) {
                return VerificationResult.UNVERIFIED;
            }
            String signedPayload = parts[0] + "." + parts[1];
            String expectedSignature = base64Url(hmac(secret, signedPayload.getBytes(StandardCharsets.UTF_8)));
            if (!constantTimeEquals(expectedSignature, parts[2])) {
                return VerificationResult.UNVERIFIED;
            }
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode expNode = payload.path("exp");
            if (expNode.isMissingNode() || !expNode.canConvertToLong()) {
                return VerificationResult.UNVERIFIED;
            }
            Instant expiresAt = Instant.ofEpochSecond(expNode.asLong());
            if (!expiresAt.isAfter(Instant.now())) {
                return VerificationResult.UNVERIFIED;
            }
            if (!"access".equals(payload.path("typ").asText(""))) {
                return VerificationResult.UNVERIFIED;
            }
            JsonNode uid = payload.path("uid");
            if (!uid.canConvertToLong()) {
                return VerificationResult.UNVERIFIED;
            }
            // 로컬에서 유효한 토큰도 jti를 공유 denylist로 확인한 뒤에만 받아들이며, 조회할 수 없으면 원격 /auth/me 검증으로 넘어간다.
            return switch (denylistStatus(payload.path("jti").asText(null))) {
                case DENIED -> VerificationResult.revoked();
                case UNAVAILABLE -> VerificationResult.UNVERIFIED;
                case NOT_DENIED -> VerificationResult.of(uid.asLong());
            };
        } catch (RuntimeException exception) {
            log.debug("Local JWT verification failed; falling back to remote verification", exception);
            return VerificationResult.UNVERIFIED;
        } catch (Exception exception) {
            log.debug("Local JWT verification failed; falling back to remote verification", exception);
            return VerificationResult.UNVERIFIED;
        }
    }

    // jti가 공유 denylist에 있는지 확인하고, denylist가 꺼졌거나 없거나 조회에 실패하면 UNAVAILABLE을 반환한다.
    // 호출자가 폐기 여부를 확인하지 않은 채 토큰을 받아들이지 않게 하기 위함이다.
    DenylistStatus denylistStatus(String jwtId) {
        if (!authProperties.isDenylistRedisEnabled()
                || denylistRedisTemplate == null
                || jwtId == null
                || jwtId.isBlank()) {
            return DenylistStatus.UNAVAILABLE;
        }
        try {
            Boolean denied = denylistRedisTemplate.hasKey(authProperties.getDenylistKeyPrefix() + jwtId);
            if (denied == null) {
                return DenylistStatus.UNAVAILABLE;
            }
            return denied ? DenylistStatus.DENIED : DenylistStatus.NOT_DENIED;
        } catch (RuntimeException exception) {
            log.warn("Gateway denylist lookup failed; falling back to remote verification", exception);
            return DenylistStatus.UNAVAILABLE;
        }
    }

    // 원격 인증 성공을 캐시할 때 필요한 jti/exp를 읽는다 (둘 중 하나라도 없으면 null → 캐시하지 않음)
    // 서명은 검증하지 않는다. user-service가 인증을 마친 뒤에만 호출되고, 값은 캐시 수명·무효화에만 쓰인다.
    CacheableClaims extractCacheableClaims(String authorization) {
        try {
            String[] parts = authorization.substring(7).split("\\.");
            if (parts.length != 3) {
                return null;
            }
            JsonNode payload = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String jwtId = payload.path("jti").asText(null);
            if (jwtId == null || jwtId.isBlank()) {
                return null;
            }
            JsonNode expNode = payload.path("exp");
            if (expNode.isMissingNode() || !expNode.canConvertToLong()) {
                return null;
            }
            return new CacheableClaims(jwtId, Instant.ofEpochSecond(expNode.asLong()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    // 캐시 엔트리의 무효화(jti)와 수명 상한(exp)에 쓰이는 최소 클레임
    record CacheableClaims(String jwtId, Instant expiresAt) {
    }

    // 공유 denylist 조회 결과(폐기됨 / 폐기 안 됨 / 확인 불가)
    enum DenylistStatus {
        DENIED,
        NOT_DENIED,
        UNAVAILABLE
    }

    // 로컬 검증 결과: 성공이면 userId, 폐기된 토큰이면 denied=true, 둘 다 아니면 UNVERIFIED(원격 검증으로 넘김)
    record VerificationResult(Long userId, boolean denied) {
        private static final VerificationResult UNVERIFIED = new VerificationResult(null, false);

        private static VerificationResult revoked() {
            return new VerificationResult(null, true);
        }

        private static VerificationResult of(long userId) {
            return new VerificationResult(userId, false);
        }
    }

    // secret으로 HmacSHA512 서명을 계산하고, 실패하면 IllegalStateException을 던진다.
    private static byte[] hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to compute HMAC", exception);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // 타이밍 공격을 막기 위해 두 문자열을 길이 확인 후 상수 시간으로 비교한다.
    private static boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        if (leftBytes.length != rightBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < leftBytes.length; i++) {
            result |= leftBytes[i] ^ rightBytes[i];
        }
        return result == 0;
    }
}
