package com.tenderpocket.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncRetryTrackerTest {

    /** Clock the test can move forward. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-16T00:30:00Z");

        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private MutableClock clock;
    private SyncRetryTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        tracker = new SyncRetryTracker(clock);
        tracker.configure(true, 120, 3);
    }

    @Test
    @DisplayName("A failed portal becomes due only after two hours")
    void retryIsDueAfterDelay() {
        assertTrue(tracker.recordFailure("MH", "timeout", List.of(), false));
        clock.advance(Duration.ofMinutes(119));
        assertTrue(tracker.takeDue().isEmpty());
        clock.advance(Duration.ofMinutes(1));
        List<SyncRetryTracker.PendingRetry> due = tracker.takeDue();
        assertEquals(1, due.size());
        assertEquals("MH", due.get(0).key);
        assertEquals(1, due.get(0).attemptsUsed);
    }

    @Test
    @DisplayName("Only the failed sources are retried, each on its own timer")
    void independentSources() {
        tracker.recordFailure("MH", "blocked", List.of(), false);
        clock.advance(Duration.ofMinutes(60));
        tracker.recordFailure(SyncRetryTracker.GEM, "2 keyword(s) failed", List.of("wheelchair", "stretcher"), false);
        clock.advance(Duration.ofMinutes(60));

        List<SyncRetryTracker.PendingRetry> due = tracker.takeDue();
        assertEquals(List.of("MH"), due.stream().map(r -> r.key).toList());

        clock.advance(Duration.ofMinutes(60));
        due = tracker.takeDue();
        assertEquals(1, due.size());
        assertEquals(SyncRetryTracker.GEM, due.get(0).key);
        assertEquals(List.of("wheelchair", "stretcher"), due.get(0).scope);
    }

    @Test
    @DisplayName("A source gives up after the retry budget and waits for its next regular run")
    void givesUpAfterMaxAttempts() {
        tracker.recordFailure("UP", "timeout", List.of(), false);
        for (int attempt = 1; attempt <= 3; attempt++) {
            clock.advance(Duration.ofMinutes(120));
            List<SyncRetryTracker.PendingRetry> due = tracker.takeDue();
            assertEquals(1, due.size(), "retry " + attempt + " should be due");
            assertEquals(attempt, due.get(0).attemptsUsed);
            boolean scheduled = tracker.recordFailure("UP", "timeout", List.of(), true);
            assertEquals(attempt < 3, scheduled);
        }
        clock.advance(Duration.ofHours(10));
        assertTrue(tracker.takeDue().isEmpty());
        assertTrue(tracker.snapshot().isEmpty());

        // The next regular run failing starts a fresh budget.
        assertTrue(tracker.recordFailure("UP", "timeout", List.of(), false));
    }

    @Test
    @DisplayName("A success clears the pending retry")
    void successClears() {
        tracker.recordFailure("CENTRAL", "HTTP 503", List.of(), false);
        tracker.recordSuccess("CENTRAL");
        clock.advance(Duration.ofHours(3));
        assertTrue(tracker.takeDue().isEmpty());
    }

    @Test
    @DisplayName("A retry that could not start is handed back without using an attempt")
    void putBackKeepsBudget() {
        tracker.recordFailure("KA", "timeout", List.of(), false);
        clock.advance(Duration.ofMinutes(120));
        tracker.putBack(tracker.takeDue());
        List<SyncRetryTracker.PendingRetry> again = tracker.takeDue();
        assertEquals(1, again.size());
        assertEquals(1, again.get(0).attemptsUsed);
    }

    @Test
    @DisplayName("Nothing is scheduled when retries are disabled")
    void disabled() {
        tracker.configure(false, 120, 3);
        assertFalse(tracker.recordFailure("MH", "timeout", List.of(), false));
        clock.advance(Duration.ofHours(3));
        assertTrue(tracker.takeDue().isEmpty());
    }
}
