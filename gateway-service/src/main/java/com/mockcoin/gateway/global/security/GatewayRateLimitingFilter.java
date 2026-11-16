package com.mockcoin.gateway.global.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.ObjectMapper;
import com.mockcoin.observability.MockcoinMetrics;
import com.mockcoin.shared.api.ApiResponse;
import com.mockcoin.shared.api.CommonErrorCode;
import com.mockcoin.shared.security.ratelimit.RateLimiter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// /api/** 및 admin API 요청에 IP/Auth/endpoint group 단위 rate limit을 적용하는 필터
@Component
@ConditionalOnBean(RateLimiter.class)
public class GatewayRateLimitingFilter extends OncePerRequestFilter {
    private static final String HEADER_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RESET = "X-RateLimit-Reset";

    private final GatewaySecurityHardeningProperties securityProperties;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final MockcoinMetrics metrics;
    private final GatewayRateLimitPolicy rateLimitPolicy;

    // 보안 설정, rate limiter, ObjectMapper, 메트릭, rate limit 정책을 주입받는다.
    public GatewayRateLimitingFilter(
            GatewaySecurityHardeningProperties securityProperties,
            RateLimiter rateLimiter,
            ObjectMapper objectMapper,
            MockcoinMetrics metrics,
            GatewayRateLimitPolicy rateLimitPolicy
    ) {
        this.securityProperties = securityProperties;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.rateLimitPolicy = rateLimitPolicy;
    }

    // rate limit 정책 대상(OPTIONS 제외 /api/**, 관리자 API)이 아닌 요청은 필터를 건너뛴다.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !rateLimitPolicy.appliesTo(request);
    }

    // 한도를 넘으면 429 실패 응답과 한도 헤더를 쓰고 멈추며, 통과하면 한도 헤더를 붙이고 처리 시간을 재며 진행한다.
    // rate limit이 꺼져 있으면 한도 검사 없이 처리 시간만 잰다.
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!securityProperties.isRateLimitEnabled()) {
            timed(() -> filterChain.doFilter(request, response));
            return;
        }

        RateLimitEvaluation evaluation = evaluate(request);
        if (!evaluation.allowed()) {
            metrics.rateLimitExceeded(evaluation.rejectedScope());
            writeLimitHeaders(response, evaluation.decision());
            response.setStatus(CommonErrorCode.RATE_LIMIT_EXCEEDED.status());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            objectMapper.writeValue(response.getWriter(), ApiResponse.fail(CommonErrorCode.RATE_LIMIT_EXCEEDED));
            return;
        }

        if (evaluation.decision() != null) {
            writeLimitHeaders(response, evaluation.decision());
        }
        timed(() -> filterChain.doFilter(request, response));
    }

    // 요청의 한도 규칙을 차례로 검사해 하나라도 거절하면 그 scope와 함께 거절을, 아니면 가장 빡빡한 결정을 반환한다.
    private RateLimitEvaluation evaluate(HttpServletRequest request) {
        RateLimiter.Decision effectiveDecision = null;
        for (GatewayRateLimitPolicy.Rule rule : rateLimitPolicy.rulesFor(request)) {
            RateLimiter.Decision decision = rateLimiter.check(rule.key(), rule.maxRequests(), rule.window());
            effectiveDecision = moreConstrained(effectiveDecision, decision);
            if (!decision.allowed()) {
                // 어느 한도가 거절했는지 함께 남긴다. 거절 사실만으로는 어디를 손봐야 할지 알 수 없다.
                return new RateLimitEvaluation(false, decision, rule.scope());
            }
        }
        return new RateLimitEvaluation(true, effectiveDecision, null);
    }

    // X-RateLimit-* 헤더를 쓰고, 거절이면 Retry-After와 Cache-Control: no-store를 추가한다.
    private void writeLimitHeaders(HttpServletResponse response, RateLimiter.Decision decision) {
        response.setHeader(HEADER_LIMIT, String.valueOf(decision.limit()));
        response.setHeader(HEADER_REMAINING, String.valueOf(decision.remaining()));
        response.setHeader(HEADER_RESET, String.valueOf(decision.resetAt().getEpochSecond()));
        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds(decision.resetAt())));
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }
    }

    // reset 시각까지 남은 초를 최소 1초로 계산한다.
    private static long retryAfterSeconds(Instant resetAt) {
        return Math.max(1L, Duration.between(Instant.now(), resetAt).toSeconds());
    }

    // 요청 처리를 실행하고 걸린 시간을 API 처리 시간 메트릭으로 기록한다.
    private void timed(FilterAction action) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        try {
            action.run();
        } finally {
            metrics.recordApiDurationNanos(System.nanoTime() - startNanos);
        }
    }

    // 서블릿 예외를 던질 수 있는 필터 처리 블록(timed로 처리 시간을 잴 때 쓴다)
    @FunctionalInterface
    private interface FilterAction {
        void run() throws ServletException, IOException;
    }

    // 남은 횟수가 더 적은 결정을, 같으면 reset이 더 이른 결정을 고른다.
    private static RateLimiter.Decision moreConstrained(RateLimiter.Decision current, RateLimiter.Decision candidate) {
        if (current == null) {
            return candidate;
        }
        if (candidate.remaining() < current.remaining()) {
            return candidate;
        }
        if (candidate.remaining() == current.remaining() && candidate.resetAt().isBefore(current.resetAt())) {
            return candidate;
        }
        return current;
    }

    // 규칙 평가 결과: 허용 여부, 헤더에 쓸 결정(허용 시 가장 빡빡한 규칙), 거절한 규칙의 scope
    private record RateLimitEvaluation(boolean allowed, RateLimiter.Decision decision, String rejectedScope) {
    }
}
