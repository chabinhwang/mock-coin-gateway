package com.mockcoin.gateway.admin.integration;
import java.time.Instant;
import java.util.Optional;

// admin 로그인 실패/잠금 상태 저장소 포트
// 출발지 IP별 연속 로그인 실패 횟수와 임계치를 넘은 뒤의 잠금 윈도우를 추적하며, prev-server RedisAdminLockoutStore와
// 같은 의미라 다중 pod gateway가 인스턴스별 카운터 대신 공유 잠금 윈도우를 적용한다.
public interface AdminLockoutStore {

    // IP의 누적 실패 횟수와 잠금 종료 시각(임계치 미만이면 null)
    record LockoutEntry(int failures, Instant lockedUntil) {
    }

    // 해당 IP에 실패 1건을 기록하고 잠금 임계치를 넘으면 잠금 윈도우를 시작한다
    LockoutEntry recordFailure(String ip, int maxAttempts, long lockoutDurationSeconds);

    // 해당 IP의 현재 잠금 상태를 조회한다(만료/미존재 시 empty)
    Optional<LockoutEntry> getLockout(String ip);

    // 성공 로그인 후 실패 카운터를 초기화한다
    void clear(String ip);

}
