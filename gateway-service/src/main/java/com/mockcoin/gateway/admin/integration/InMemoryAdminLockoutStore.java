package com.mockcoin.gateway.admin.integration;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mockcoin.gateway.admin.integration.AdminLockoutStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Redis 비활성(mockcoin.gateway.admin.redis-enabled=false, 기본값) 시 사용되는 admin 로그인 잠금 상태 in-memory 저장소
// 주기 정리로 만료된 잠금과 24시간 지난 부분 실패 기록을 버려, IP를 바꿔 가며 brute-force해도 맵 크기가 제한된다.
// 24시간 보존 기간은 Redis 어댑터와 같다.
@Component
@ConditionalOnMissingBean(name = "redisAdminLockoutStore")
public class InMemoryAdminLockoutStore implements AdminLockoutStore {
    private static final long FAILURE_RETENTION_SECONDS = 24L * 60 * 60;

    private final Map<String, FailureState> lockouts = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryAdminLockoutStore() {
        this(Clock.systemUTC());
    }

    // 현재 시각 계산에 쓸 시계를 받는다.
    InMemoryAdminLockoutStore(Clock clock) {
        this.clock = clock;
    }

    // 만료된 상태는 버리고 실패 횟수를 원자적으로 올리며, 임계치에 닿으면 잠금 종료 시각을 정한다.
    @Override
    public LockoutEntry recordFailure(String ip, int maxAttempts, long lockoutDurationSeconds) {
        Instant now = Instant.now(clock);
        FailureState updated = lockouts.compute(ip, (key, current) -> {
            FailureState active = current == null || isExpired(current, now) ? null : current;
            int failures = active == null ? 1 : active.entry().failures() + 1;
            Instant lockedUntil = failures >= maxAttempts
                    ? now.plusSeconds(lockoutDurationSeconds)
                    : null;
            return new FailureState(new LockoutEntry(failures, lockedUntil), now);
        });
        return updated.entry();
    }

    // 잠금 상태를 조회하고, 미존재면 empty, 만료됐으면 제거한 뒤 empty를 반환한다.
    @Override
    public Optional<LockoutEntry> getLockout(String ip) {
        FailureState state = lockouts.get(ip);
        if (state == null) {
            return Optional.empty();
        }
        if (isExpired(state, Instant.now(clock))) {
            lockouts.remove(ip, state);
            return Optional.empty();
        }
        return Optional.of(state.entry());
    }

    // ip가 null이 아니면 해당 IP의 실패/잠금 상태를 제거한다.
    @Override
    public void clear(String ip) {
        if (ip != null) {
            lockouts.remove(ip);
        }
    }

    // 만료된 잠금과 마지막 실패 후 24시간이 지난 실패 기록을 모두 제거한다.
    public void purgeExpired(Instant now) {
        lockouts.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
    }

    // 주기적으로(기본 60초) 만료된 잠금 상태를 정리해 맵 크기를 제한한다.
    @Scheduled(fixedDelayString = "${mockcoin.gateway.admin.in-memory-sweep-millis:60000}")
    void scheduledPurge() {
        purgeExpired(Instant.now(clock));
    }

    // 잠금이 있으면 잠금 종료 시각이, 없으면 마지막 실패 후 24시간이 지났는지 확인한다.
    private static boolean isExpired(FailureState state, Instant now) {
        Instant lockedUntil = state.entry().lockedUntil();
        if (lockedUntil != null) {
            return lockedUntil.isBefore(now);
        }
        return state.lastFailureAt().plusSeconds(FAILURE_RETENTION_SECONDS).isBefore(now);
    }

    // 잠금 상태와 마지막 실패 시각(24시간 보존 만료 판단용)
    private record FailureState(LockoutEntry entry, Instant lastFailureAt) {
    }
}
