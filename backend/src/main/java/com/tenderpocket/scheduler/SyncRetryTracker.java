package com.tenderpocket.scheduler;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which scheduled syncs failed (GeM, or one state / central portal) and when each may be
 * retried. A failure of a regular cron run starts a fresh retry budget; a failed retry uses one
 * attempt of it, and once the budget is spent the source waits for its next regular run. A success
 * clears the entry. State is in memory only: a restart forgets pending retries, and the regular
 * runs pick the work up again.
 */
@Component
public class SyncRetryTracker {

    /** Key for the GeM portal; state portals use their code from state-portals.json (CENTRAL, MH...). */
    public static final String GEM = "GEM";
    /** Key for the Tender247 alert-email import. */
    public static final String TENDER247 = "TENDER247";

    private final Map<String, PendingRetry> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    @Value("${sync.retry.enabled:true}")
    private boolean enabled = true;

    @Value("${sync.retry.delay-minutes:120}")
    private long delayMinutes = 120;

    @Value("${sync.retry.max-attempts:3}")
    private int maxAttempts = 3;

    public SyncRetryTracker() {
        this(Clock.systemUTC());
    }

    SyncRetryTracker(Clock clock) {
        this.clock = clock;
    }

    void configure(boolean enabled, long delayMinutes, int maxAttempts) {
        this.enabled = enabled;
        this.delayMinutes = delayMinutes;
        this.maxAttempts = maxAttempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public boolean isEnabled() {
        return enabled && maxAttempts > 0;
    }

    /**
     * Records a failed run and schedules a retry if the budget allows.
     *
     * @param key       {@link #GEM} or a state portal code
     * @param reason    short error text for the log and the status endpoint
     * @param scope     what to retry: GeM keywords that failed (empty means the whole source)
     * @param fromRetry true when the failed run was itself a retry
     * @return true when a retry was scheduled
     */
    public boolean recordFailure(String key, String reason, List<String> scope, boolean fromRetry) {
        if (!isEnabled()) return false;
        PendingRetry previous = pending.get(key);
        int attemptsUsed = fromRetry && previous != null ? previous.attemptsUsed : 0;
        if (attemptsUsed >= maxAttempts) {
            pending.remove(key);
            System.err.printf("[Retry] %s still failing after %d retr%s (%s); waiting for its next regular run.%n",
                    key, attemptsUsed, attemptsUsed == 1 ? "y" : "ies", reason);
            return false;
        }
        Instant due = clock.instant().plus(Duration.ofMinutes(delayMinutes));
        pending.put(key, new PendingRetry(key, due, attemptsUsed, reason, scope == null ? List.of() : List.copyOf(scope), false));
        System.out.printf("[Retry] %s failed (%s); retry %d/%d in %d min.%n",
                key, reason, attemptsUsed + 1, maxAttempts, delayMinutes);
        return true;
    }

    /** Clears any pending retry for a source that has just synced successfully. */
    public void recordSuccess(String key) {
        if (pending.remove(key) != null) {
            System.out.printf("[Retry] %s synced successfully; pending retry cleared.%n", key);
        }
    }

    /**
     * Takes the retries that are due and counts the attempt against each. The caller must report
     * the outcome with {@link #recordFailure} (fromRetry = true) or {@link #recordSuccess}; if it
     * cannot start the retry now, it hands the entries back with {@link #putBack}.
     */
    public synchronized List<PendingRetry> takeDue() {
        Instant now = clock.instant();
        List<PendingRetry> due = new ArrayList<>();
        for (PendingRetry r : new ArrayList<>(pending.values())) {
            if (r.inFlight || r.dueAt.isAfter(now)) continue;
            PendingRetry taken = r.taken(r.attemptsUsed + 1, true);
            pending.put(r.key, taken);
            due.add(taken);
        }
        return due;
    }

    /** Returns retries that could not start (another sync was running); they are tried on the next check. */
    public synchronized void putBack(List<PendingRetry> retries) {
        for (PendingRetry r : retries) {
            pending.computeIfPresent(r.key, (k, current) -> current.taken(r.attemptsUsed - 1, false));
        }
    }

    /** The pending retry for one source, if any. */
    public java.util.Optional<PendingRetry> find(String key) {
        return java.util.Optional.ofNullable(pending.get(key));
    }

    /** Pending retries for the status endpoint. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PendingRetry r : pending.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", r.key);
            m.put("dueAt", r.dueAt.toString());
            m.put("attemptsUsed", r.attemptsUsed);
            m.put("running", r.inFlight);
            m.put("maxAttempts", maxAttempts);
            m.put("reason", r.reason);
            if (!r.scope.isEmpty()) m.put("scope", r.scope);
            list.add(m);
        }
        return list;
    }

    /** One source waiting for a retry. */
    public static final class PendingRetry {
        public final String key;
        public final Instant dueAt;
        public final int attemptsUsed;
        public final String reason;
        public final List<String> scope;
        /** Taken by {@link #takeDue} and not yet reported back, so it is not started twice. */
        public final boolean inFlight;

        PendingRetry(String key, Instant dueAt, int attemptsUsed, String reason, List<String> scope, boolean inFlight) {
            this.key = key;
            this.dueAt = dueAt;
            this.attemptsUsed = attemptsUsed;
            this.reason = reason;
            this.scope = scope;
            this.inFlight = inFlight;
        }

        PendingRetry taken(int attempts, boolean inFlight) {
            return new PendingRetry(key, dueAt, attempts, reason, scope, inFlight);
        }
    }
}
