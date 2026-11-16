package com.mockcoin.gateway.admin.integration;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import com.mockcoin.gateway.admin.integration.AdminLockoutStore;
import com.mockcoin.gateway.admin.integration.AdminLockoutStore.LockoutEntry;
import org.junit.jupiter.api.Test;

class InMemoryAdminLockoutStoreTest {

    private final InMemoryAdminLockoutStore store = new InMemoryAdminLockoutStore();

    @Test
    void recordFailureIncrementsCounterWithoutLockUntilThreshold() {
        LockoutEntry first = store.recordFailure("1.2.3.4", 3, 60);
        LockoutEntry second = store.recordFailure("1.2.3.4", 3, 60);

        assertThat(first.failures()).isEqualTo(1);
        assertThat(first.lockedUntil()).isNull();
        assertThat(second.failures()).isEqualTo(2);
        assertThat(second.lockedUntil()).isNull();
    }

    @Test
    void recordFailureSetsLockoutWhenThresholdReached() {
        store.recordFailure("1.2.3.4", 2, 60);
        LockoutEntry locked = store.recordFailure("1.2.3.4", 2, 60);

        assertThat(locked.failures()).isEqualTo(2);
        assertThat(locked.lockedUntil()).isNotNull();
        assertThat(store.getLockout("1.2.3.4")).isPresent();
    }

    @Test
    void clearRemovesEntry() {
        store.recordFailure("1.2.3.4", 5, 60);
        store.clear("1.2.3.4");

        assertThat(store.getLockout("1.2.3.4")).isEmpty();
    }

    @Test
    void getLockoutReturnsEmptyAfterWindowExpires() {
        store.recordFailure("1.2.3.4", 1, 0);
        Instant afterRecord = Instant.now();

        // lockedUntil = (recordFailure 시점) + 0s 이므로 afterRecord 이전이다. getLockout은 strict
        // isBefore(now)로 만료를 판정하므로, 두 Instant.now()가 같은 tick일 때 발생하던 레이스를 없애기
        // 위해 벽시계가 afterRecord 이후로 진행할 때까지 spin-wait한다(sleep 없이 결정적).
        while (!Instant.now().isAfter(afterRecord)) {
            Thread.onSpinWait();
        }

        assertThat(store.getLockout("1.2.3.4")).isEmpty();
    }

    @Test
    void purgeExpiredDropsLockedEntriesPastWindow() {
        store.recordFailure("expired-ip", 1, 0);
        store.recordFailure("alive-ip", 1, 600);

        store.purgeExpired(Instant.now());

        assertThat(store.getLockout("expired-ip")).isEmpty();
        assertThat(store.getLockout("alive-ip")).isPresent();
    }

    @Test
    void purgeExpiredDropsPartialFailureAfterRetentionWindow() {
        store.recordFailure("partial-ip", 5, 600);

        store.purgeExpired(Instant.now().plusSeconds(24L * 60 * 60 + 1));

        assertThat(store.getLockout("partial-ip")).isEmpty();
    }

    @Test
    void purgeExpiredKeepsPartialFailureInsideRetentionWindow() {
        store.recordFailure("partial-ip", 5, 600);

        store.purgeExpired(Instant.now().plusSeconds(23L * 60 * 60));

        assertThat(store.getLockout("partial-ip")).isPresent();
    }

    @Test
    void recordFailureResetsExpiredPartialStateAtRetentionBoundary() {
        Instant base = Instant.parse("2026-07-12T00:00:00Z");
        MutableClock boundaryClock = new MutableClock(base);
        InMemoryAdminLockoutStore boundaryStore = new InMemoryAdminLockoutStore(boundaryClock);
        boundaryStore.recordFailure("boundary-ip", 5, 600);
        boundaryClock.set(base.plusSeconds(24L * 60 * 60));

        assertThat(boundaryStore.recordFailure("boundary-ip", 5, 600).failures()).isEqualTo(2);

        MutableClock expiredClock = new MutableClock(base);
        InMemoryAdminLockoutStore expiredStore = new InMemoryAdminLockoutStore(expiredClock);
        expiredStore.recordFailure("expired-ip", 5, 600);
        expiredClock.set(base.plusSeconds(24L * 60 * 60).plusNanos(1));

        assertThat(expiredStore.recordFailure("expired-ip", 5, 600).failures()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
}
