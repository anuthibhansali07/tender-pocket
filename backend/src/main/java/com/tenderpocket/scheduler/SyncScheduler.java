package com.tenderpocket.scheduler;

import com.tenderpocket.models.PortalFetchStatus;
import com.tenderpocket.services.EmailSyncService;
import com.tenderpocket.services.FetchStatusRecorder;
import com.tenderpocket.services.GeMScraperService;
import com.tenderpocket.services.state.GepnicPortal;
import com.tenderpocket.services.state.StateTenderSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.tenderpocket.scheduler.SyncFailureAlertService.Event;
import static com.tenderpocket.scheduler.SyncFailureAlertService.Incident;
import static com.tenderpocket.scheduler.SyncFailureAlertService.TIME;

@Component
public class SyncScheduler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String GEM_URL = "https://bidplus.gem.gov.in/all-bids";
    static final String JOB_GEM = "Scheduled GeM import";
    static final String JOB_STATE_DAILY = "Daily state & central portal import";
    static final String JOB_STATE_FREQUENT = "Frequent portal check";
    static final String JOB_RETRY = "Automatic retry of failed imports";
    static final String JOB_TENDER247 = "Scheduled Tender247 email import";
    static final String JOB_STATE_STARTUP = "Startup catch-up of state & central portals";
    private static final String TENDER247_NAME = "Tender247 email alerts";
    private static final String TENDER247_URL = "https://www.tender247.com/";

    /** The "no inbox set up" email goes out once per backend start, not on every run. */
    private final AtomicBoolean tender247NotSetUpReported = new AtomicBoolean(false);

    @Autowired
    private EmailSyncService emailSyncService;

    @Autowired
    private GeMScraperService geMScraperService;

    @Autowired
    private StateTenderSyncService stateTenderSyncService;

    @Autowired
    private SyncRetryTracker retryTracker;

    @Autowired
    private SyncFailureAlertService alerts;

    @Autowired(required = false)
    private FetchStatusRecorder fetchStatusRecorder;

    /** Skip the startup import when GeM was read more recently than this. */
    @Value("${gem.sync.startup-if-older-than-hours:6}")
    private long startupIfOlderThanHours = 6;

    /** The Tender247 + GeM import runs one at a time, whichever trigger started it. */
    private final AtomicBoolean importRunning = new AtomicBoolean(false);

    // GeM + Tender247 at fixed times (default 7:30 AM, 1:30 PM and 7:30 PM IST), after the 6:00 AM
    // state import has finished. Fixed times instead of "every 6 hours from boot" keep the load
    // predictable and avoid back-to-back runs.
    @Scheduled(cron = "${gem.sync.cron:0 30 7,13,19 * * *}", zone = "Asia/Kolkata")
    public void runScheduledImport() {
        System.out.println("[Scheduler] Running scheduled GeM & Tender247 import...");
        performSync();
    }

    // Once, 15 s after startup, and only if GeM has not been read for a while: restarts (deploys,
    // local development) no longer start a full GeM import every time.
    @Scheduled(initialDelay = 15000)
    public void runStartupSync() {
        Optional<PortalFetchStatus> gem = fetchStatusRecorder != null
                ? fetchStatusRecorder.find(SyncRetryTracker.GEM) : Optional.empty();
        if (!shouldRunOnStartup(gem.map(PortalFetchStatus::getLastReachedAt).orElse(null),
                LocalDateTime.now(IST), startupIfOlderThanHours)) {
            System.out.println("[Scheduler] Startup import skipped: GeM was read at "
                    + gem.get().getLastReachedAt() + "; the next scheduled import will run as usual.");
            return;
        }
        System.out.println("[Scheduler] Running startup GeM & Tender247 import (no recent import found)...");
        performSync();
    }

    // ---- Manual re-check from the Fetch Status page ----------------------------------------

    static final String JOB_MANUAL = "Manual re-check from Fetch Status page";

    /** Minimum gap between two manual re-checks of the same source, so repeated clicks can't hammer a portal. */
    @Value("${fetch-status.manual-cooldown-minutes:30}")
    private long manualCooldownMinutes = 30;

    private final Map<String, LocalDateTime> lastManualRecheck = new java.util.concurrent.ConcurrentHashMap<>();

    public enum RecheckOutcome { STARTED, ALREADY_RUNNING, COOLDOWN, UNKNOWN_SOURCE, DISABLED, NOT_SET_UP }

    /** Result of a manual re-check request; {@code availableAt} is set for COOLDOWN. */
    public record RecheckResult(RecheckOutcome outcome, LocalDateTime availableAt, int keywordCount) { }

    /**
     * Re-checks one source now: a single state/UT/central portal, or GeM. For GeM only the product
     * keywords that did not finish last time are searched (all keywords if the whole run failed).
     * The run is recorded, retried and alerted exactly like a scheduled one.
     */
    public RecheckResult startManualRecheck(String source) {
        String key = source == null ? "" : source.trim().toUpperCase(java.util.Locale.ROOT);
        if (SyncRetryTracker.TENDER247.equals(key)) return startManualTender247Recheck(key);
        boolean isGem = SyncRetryTracker.GEM.equals(key);
        GepnicPortal portal = isGem ? null : stateTenderSyncService.getPortals().stream()
                .filter(p -> p.code().equals(key) && p.enabled()).findFirst().orElse(null);
        if (!isGem && portal == null) return new RecheckResult(RecheckOutcome.UNKNOWN_SOURCE, null, 0);
        if (!isGem && !stateTenderSyncService.isEnabled()) return new RecheckResult(RecheckOutcome.DISABLED, null, 0);

        LocalDateTime now = LocalDateTime.now(IST);
        LocalDateTime availableAt = nextManualRecheckAt(lastManualRecheck.get(key), manualCooldownMinutes);
        if (availableAt != null && now.isBefore(availableAt)) {
            return new RecheckResult(RecheckOutcome.COOLDOWN, availableAt, 0);
        }

        if (isGem) {
            if (geMScraperService.isSyncRunning()) return new RecheckResult(RecheckOutcome.ALREADY_RUNNING, null, 0);
            List<String> keywords = gemKeywordsToRecheck();
            lastManualRecheck.put(key, now);
            Thread t = new Thread(() -> runGemSync(keywords, false, JOB_MANUAL), "gem-manual-recheck");
            t.setDaemon(true);
            t.start();
            System.out.println("[Manual] GeM re-check started for "
                    + (keywords.isEmpty() ? "all keywords" : keywords.size() + " keyword(s)"));
            return new RecheckResult(RecheckOutcome.STARTED, null, keywords.size());
        }

        boolean started = stateTenderSyncService.startAsync(List.of(key), false,
                summary -> recordStateOutcome(summary, false, JOB_MANUAL));
        if (!started) return new RecheckResult(RecheckOutcome.ALREADY_RUNNING, null, 0);
        lastManualRecheck.put(key, now);
        System.out.println("[Manual] Re-check started for portal " + key);
        return new RecheckResult(RecheckOutcome.STARTED, null, 0);
    }

    /** Reads the Tender247 alert inbox again now (unread alert emails from the last 2 days). */
    private RecheckResult startManualTender247Recheck(String key) {
        if (!emailSyncService.isConfigured()) return new RecheckResult(RecheckOutcome.NOT_SET_UP, null, 0);
        LocalDateTime now = LocalDateTime.now(IST);
        LocalDateTime availableAt = nextManualRecheckAt(lastManualRecheck.get(key), manualCooldownMinutes);
        if (availableAt != null && now.isBefore(availableAt)) {
            return new RecheckResult(RecheckOutcome.COOLDOWN, availableAt, 0);
        }
        if (emailSyncService.isSyncRunning()) return new RecheckResult(RecheckOutcome.ALREADY_RUNNING, null, 0);
        lastManualRecheck.put(key, now);
        Thread t = new Thread(() -> runTender247Sync(false, JOB_MANUAL), "tender247-manual-recheck");
        t.setDaemon(true);
        t.start();
        System.out.println("[Manual] Tender247 re-check started");
        return new RecheckResult(RecheckOutcome.STARTED, null, 0);
    }

    /** GeM keywords that did not finish last time; empty means search all keywords. */
    private List<String> gemKeywordsToRecheck() {
        Optional<PortalFetchStatus> gem = fetchStatusRecorder != null
                ? fetchStatusRecorder.find(SyncRetryTracker.GEM) : Optional.empty();
        return gem.filter(s -> PortalFetchStatus.PARTIAL.equals(s.getOutcome()))
                .map(PortalFetchStatus::getMissedKeywords)
                .map(m -> java.util.Arrays.stream(m.split(",")).map(String::trim).filter(k -> !k.isEmpty()).toList())
                .orElse(List.of());
    }

    /** When the next manual re-check may start, or null when there was none before. */
    static LocalDateTime nextManualRecheckAt(LocalDateTime lastManual, long cooldownMinutes) {
        return lastManual == null ? null : lastManual.plusMinutes(cooldownMinutes);
    }

    /** True when GeM was never read, or not within the last {@code hours} hours. */
    static boolean shouldRunOnStartup(LocalDateTime lastReachedAt, LocalDateTime now, long hours) {
        return lastReachedAt == null || lastReachedAt.isBefore(now.minusHours(hours));
    }

    // State & central NIC eProcurement portals, daily (default 6:00 AM IST). Runs on its own
    // thread pool and returns immediately, so it never delays the GeM / Tender247 jobs below.
    @Scheduled(cron = "${state.sync.cron:0 0 6 * * *}", zone = "Asia/Kolkata")
    public void runStatePortalSync() {
        if (!stateTenderSyncService.isEnabled()) return;
        boolean started = stateTenderSyncService.startAsync(Collections.emptyList(), false,
                summary -> recordStateOutcome(summary, false, JOB_STATE_DAILY));
        System.out.println(started
                ? "[Scheduler] State portal sync started in the background."
                : "[Scheduler] State portal sync already running; skipped this trigger.");
    }

    /** At startup, portals not checked for this long are checked (the 6 AM run is missed when the app was off). */
    @Value("${state.sync.startup-if-older-than-hours:20}")
    private long stateStartupIfOlderThanHours = 20;

    // Once, 60 s after startup: the daily state import only runs at 6:00 AM, so a backend started
    // later in the day (or restarted) would otherwise wait until tomorrow. Only portals whose last
    // check is older than state.sync.startup-if-older-than-hours are read. Runs on the state
    // sync's own pool, alongside the GeM startup import (different websites).
    @Scheduled(initialDelayString = "${state.sync.startup-delay-ms:60000}")
    public void runStartupStateCatchUp() {
        if (!stateTenderSyncService.isEnabled()) return;
        LocalDateTime now = LocalDateTime.now(IST);
        List<String> stale = stateTenderSyncService.getPortals().stream()
                .filter(GepnicPortal::enabled)
                .map(GepnicPortal::code)
                .filter(code -> isStale(fetchStatusRecorder == null ? null
                        : fetchStatusRecorder.find(code).map(PortalFetchStatus::getLastAttemptAt).orElse(null),
                        now, stateStartupIfOlderThanHours))
                .toList();
        if (stale.isEmpty()) {
            System.out.println("[Scheduler] Startup state check skipped: every portal was checked in the last "
                    + stateStartupIfOlderThanHours + " hours.");
            return;
        }
        boolean started = stateTenderSyncService.startAsync(stale, false,
                summary -> recordStateOutcome(summary, false, JOB_STATE_STARTUP));
        System.out.println(started
                ? "[Scheduler] Startup state check started for " + stale.size() + " portal(s) not checked recently: " + String.join(",", stale)
                : "[Scheduler] Startup state check skipped: a state sync is already running.");
    }

    /** True when a source was never checked, or not within the last {@code hours} hours. */
    static boolean isStale(LocalDateTime lastCheckedAt, LocalDateTime now, long hours) {
        return lastCheckedAt == null || lastCheckedAt.isBefore(now.minusHours(hours));
    }

    // Portals that only expose their newest few tenders without a CAPTCHA (Manipur lists 10) are
    // checked more often so a busy day does not push tenders out of view before the daily run.
    @Scheduled(cron = "${state.sync.frequent-cron:0 30 */3 * * *}", zone = "Asia/Kolkata")
    public void runFrequentStatePortalSync() {
        if (!stateTenderSyncService.isEnabled()) return;
        java.util.List<String> codes = stateTenderSyncService.getFrequentPortalCodes();
        if (codes.isEmpty()) return;
        boolean started = stateTenderSyncService.startAsync(codes, false,
                summary -> recordStateOutcome(summary, false, JOB_STATE_FREQUENT));
        if (!started) System.out.println("[Scheduler] Frequent state portal check skipped: a sync is already running.");
    }

    /**
     * Every few minutes, re-runs the GeM keywords and state/central portals whose scheduled sync
     * failed at least {@code sync.retry.delay-minutes} (default 120) ago. Only the failed parts are
     * repeated, never the whole set of portals.
     */
    @Scheduled(initialDelayString = "${sync.retry.check-interval-ms:300000}",
            fixedDelayString = "${sync.retry.check-interval-ms:300000}")
    public void runDueRetries() {
        if (!retryTracker.isEnabled()) return;
        List<SyncRetryTracker.PendingRetry> due = retryTracker.takeDue();
        if (due.isEmpty()) return;

        SyncRetryTracker.PendingRetry gem = null;
        SyncRetryTracker.PendingRetry tender247 = null;
        List<SyncRetryTracker.PendingRetry> stateRetries = new ArrayList<>();
        for (SyncRetryTracker.PendingRetry r : due) {
            if (SyncRetryTracker.GEM.equals(r.key)) gem = r;
            else if (SyncRetryTracker.TENDER247.equals(r.key)) tender247 = r;
            else stateRetries.add(r);
        }

        if (tender247 != null) {
            System.out.println("[Retry] Retrying Tender247 alert emails that were not read...");
            runTender247Sync(true, JOB_RETRY);
        }

        if (!stateRetries.isEmpty()) {
            List<String> codes = stateRetries.stream().map(r -> r.key).toList();
            boolean started = stateTenderSyncService.isEnabled()
                    && stateTenderSyncService.startAsync(codes, false, summary -> recordStateOutcome(summary, true, JOB_RETRY));
            if (started) {
                System.out.println("[Retry] Retrying state/central portal(s): " + String.join(",", codes));
            } else {
                // Another state sync is running; try again on the next check.
                retryTracker.putBack(stateRetries);
            }
        }

        if (gem != null) {
            System.out.println("[Retry] Retrying GeM sync for "
                    + (gem.scope.isEmpty() ? "all keywords" : gem.scope.size() + " failed keyword(s)") + "...");
            runGemSync(gem.scope, true);
        }
    }

    private void performSync() {
        if (!importRunning.compareAndSet(false, true)) {
            System.out.println("[Scheduler] GeM & Tender247 import already running; skipped this trigger.");
            return;
        }
        try {
            doPerformSync();
        } finally {
            importRunning.set(false);
        }
    }

    private void doPerformSync() {
        LocalDateTime startedAt = LocalDateTime.now(IST);
        try {
            // 1. Sync emails from Tender247 (if credentials provided)
            runTender247Sync(false, JOB_TENDER247);

            // 2. Sync GeM portal bids (published in the last 2 days)
            runGemSync(Collections.emptyList(), false);

        } catch (Exception e) {
            System.err.println("[Scheduler] Automated execution encountered an error: " + e.getMessage());
            alerts.report("Tender import run", startedAt, List.of(
                    new Incident(Event.FAILED, "SCHEDULER", "Tender import run", describe(e))));
        }
    }

    /**
     * Imports the Tender247 alert emails, records the outcome for the 2-hour retry and emails the
     * tech team on failure. Emails that could not be read stay unread, so a retry picks them up.
     */
    private void runTender247Sync(boolean fromRetry, String job) {
        String key = SyncRetryTracker.TENDER247;
        LocalDateTime startedAt = LocalDateTime.now(IST);
        long t0 = System.currentTimeMillis();
        Optional<SyncRetryTracker.PendingRetry> pendingBefore = retryTracker.find(key);
        try {
            EmailSyncService.SyncResult result;
            try {
                result = emailSyncService.syncEmailsDetailed();
            } catch (EmailSyncService.SyncAlreadyRunningException busy) {
                System.out.println("[Scheduler] Tender247 import skipped: another one is already reading the inbox.");
                if (fromRetry) retryTracker.find(key).ifPresent(r -> retryTracker.putBack(List.of(r)));
                return;
            }
            if (!result.configured) {
                retryTracker.recordSuccess(key); // nothing a retry could fix
                if (tender247NotSetUpReported.compareAndSet(false, true)) {
                    alerts.report(job, startedAt, List.of(new Incident(Event.FAILED, key, TENDER247_NAME,
                            "No Tender247 alert inbox is set up (IMAP_USERNAME / IMAP_PASSWORD are empty), "
                                    + "so no Tender247 tenders are being imported")
                            .detail("Noticed at", TIME.format(LocalDateTime.now(IST)))
                            .detail("What to do", "Set IMAP_USERNAME, IMAP_PASSWORD (Gmail app password) and "
                                    + "IMAP_SENDER_FILTER=sales@tender247.com in backend/.env, then restart the backend")
                            .detail("Repeats", "Sent once per backend start; the Fetch Status page shows it until fixed")));
                }
                return;
            }
            tender247NotSetUpReported.set(false);
            System.out.println("[Scheduler] Tender247 import completed. Imported: " + result.imported
                    + (result.hasFailures() ? ", alert emails not read: " + result.failedEmails.size() : ""));
            if (result.hasFailures()) {
                String reason = result.failedEmails.size() + " Tender247 alert email(s) could not be read; "
                        + "they were left unread to try again";
                boolean scheduled = retryTracker.recordFailure(key, reason, List.of(), fromRetry);
                Incident i = new Incident(event(fromRetry, scheduled), key, TENDER247_NAME, reason)
                        .detail("Alert emails read", result.emailsRead)
                        .detail("Alert emails that failed", String.join("\n", result.failedEmails))
                        .detail("New tenders imported in this run", result.imported);
                addRunFacts(i, startedAt, t0, key, TENDER247_URL, scheduled);
                alerts.report(job, startedAt, List.of(i));
            } else {
                retryTracker.recordSuccess(key);
                if (fromRetry && pendingBefore.isPresent()) {
                    alerts.report(job, startedAt, List.of(recovered(key, TENDER247_NAME, pendingBefore.get(), result.imported)));
                }
            }
        } catch (Exception e) {
            System.err.println("[Scheduler] Tender247 import failed: " + describe(e));
            boolean scheduled = retryTracker.recordFailure(key, describe(e), List.of(), fromRetry);
            Incident i = new Incident(event(fromRetry, scheduled), key, TENDER247_NAME, describe(e))
                    .detail("What to check", "IMAP host, username/app password and mailbox access");
            addRunFacts(i, startedAt, t0, key, TENDER247_URL, scheduled);
            alerts.report(job, startedAt, List.of(i));
        }
    }

    /** Runs a GeM sync, records the outcome for the 2-hour retry and emails the tech team on failure. */
    private void runGemSync(List<String> keywords, boolean fromRetry) {
        runGemSync(keywords, fromRetry, fromRetry ? JOB_RETRY : JOB_GEM);
    }

    private void runGemSync(List<String> keywords, boolean fromRetry, String job) {
        LocalDateTime startedAt = LocalDateTime.now(IST);
        long t0 = System.currentTimeMillis();
        Optional<SyncRetryTracker.PendingRetry> pendingBefore = retryTracker.find(SyncRetryTracker.GEM);
        String keywordScope = keywords.isEmpty() ? "All product keywords" : keywords.size() + " keyword(s) from the earlier failure";
        try {
            GeMScraperService.SyncResult result;
            try {
                result = geMScraperService.syncTendersDetailed(false, keywords);
            } catch (GeMScraperService.SyncAlreadyRunningException busy) {
                // Someone started GeM by hand; not a failure. A retry stays due for the next check.
                System.out.println("[Scheduler] GeM sync skipped: another GeM sync is already running.");
                if (fromRetry) retryTracker.find(SyncRetryTracker.GEM).ifPresent(r -> retryTracker.putBack(List.of(r)));
                return;
            }
            System.out.println("[Scheduler] GeM Sync completed. Imported: " + result.imported
                    + (result.hasFailures() ? ", failed keywords: " + result.failedKeywords.size() : ""));
            if (result.hasFailures()) {
                String reason = result.failedKeywords.size() + " keyword search(es) failed even after re-login "
                        + "(GeM refused or timed out; often rate limiting)";
                boolean scheduled = retryTracker.recordFailure(SyncRetryTracker.GEM, reason, result.failedKeywords, fromRetry);
                Incident i = new Incident(event(fromRetry, scheduled), SyncRetryTracker.GEM, "GeM", reason)
                        .detail("Keywords searched", keywordScope)
                        .detail("Failed / not reached keywords", String.join(", ", result.failedKeywords))
                        .detail("New tenders imported in this run", result.imported);
                addRunFacts(i, startedAt, t0, SyncRetryTracker.GEM, GEM_URL, scheduled);
                alerts.report(job, startedAt, List.of(i));
            } else {
                retryTracker.recordSuccess(SyncRetryTracker.GEM);
                if (fromRetry && pendingBefore.isPresent()) {
                    alerts.report(job, startedAt, List.of(recovered(SyncRetryTracker.GEM, "GeM", pendingBefore.get(), result.imported)));
                }
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.println("[Scheduler] GeM Sync failed: " + errorMsg);
            // Nothing finished: retry the same keywords (empty = all).
            boolean scheduled = retryTracker.recordFailure(SyncRetryTracker.GEM, errorMsg, keywords, fromRetry);
            Incident i = new Incident(event(fromRetry, scheduled), SyncRetryTracker.GEM, "GeM", describe(e))
                    .detail("Keywords searched", keywordScope);
            addRunFacts(i, startedAt, t0, SyncRetryTracker.GEM, GEM_URL, scheduled);
            alerts.report(job, startedAt, List.of(i));
        }
    }

    /** Schedules a retry for each portal whose run failed, clears retries for those that succeeded, and emails the tech team. */
    void recordStateOutcome(StateTenderSyncService.RunSummary summary, boolean fromRetry, String job) {
        if (summary == null || summary.dryRun) return;
        List<StateTenderSyncService.PortalResult> results;
        synchronized (summary.portals) {
            results = new ArrayList<>(summary.portals);
        }
        Map<String, GepnicPortal> portals = stateTenderSyncService.getPortals().stream()
                .collect(Collectors.toMap(GepnicPortal::code, Function.identity(), (a, b) -> a));
        List<Incident> incidents = new ArrayList<>();
        for (StateTenderSyncService.PortalResult r : results) {
            GepnicPortal portal = portals.get(r.portal);
            String name = portal != null ? portal.name() : r.portal;
            Optional<SyncRetryTracker.PendingRetry> pendingBefore = retryTracker.find(r.portal);
            if (r.error == null) {
                retryTracker.recordSuccess(r.portal);
                if (fromRetry && pendingBefore.isPresent()) {
                    incidents.add(recovered(r.portal, name, pendingBefore.get(), r.imported));
                }
            } else if (!"interrupted".equals(r.error)) { // interrupted = app shutting down
                boolean scheduled = retryTracker.recordFailure(r.portal, r.error, List.of(), fromRetry);
                Incident i = new Incident(event(fromRetry, scheduled), r.portal, name, r.error)
                        .detail("Portal type", portal == null ? null : portal.kind())
                        .detail("Departments listed before failure", r.organisations)
                        .detail("Department lists that failed", r.failures)
                        .detail("Tender rows read", r.rowsSeen)
                        .detail("New tenders imported before failure", r.imported)
                        .detail("HTTP requests made", r.requests)
                        .detail("Portal time", String.format("%.1f s", r.durationMs / 1000.0));
                LocalDateTime finished = summary.finishedAt != null ? summary.finishedAt : LocalDateTime.now(IST);
                i.detail("Run started", TIME.format(summary.startedAt));
                i.detail("Run finished", TIME.format(finished));
                addHistoryAndPlan(i, r.portal, portal == null ? null : portal.entryUrl(), scheduled);
                incidents.add(i);
            }
        }
        if (!incidents.isEmpty()) alerts.report(job, summary.startedAt, incidents);
    }

    private static Event event(boolean fromRetry, boolean retryScheduled) {
        if (!fromRetry) return Event.FAILED;
        return retryScheduled ? Event.RETRY_FAILED : Event.GAVE_UP;
    }

    private void addRunFacts(Incident i, LocalDateTime startedAt, long t0, String source, String url, boolean scheduled) {
        i.detail("Run started", TIME.format(startedAt));
        i.detail("Run finished", TIME.format(LocalDateTime.now(IST)));
        i.detail("Duration", String.format("%.1f min", (System.currentTimeMillis() - t0) / 60000.0));
        addHistoryAndPlan(i, source, url, scheduled);
    }

    private void addHistoryAndPlan(Incident i, String source, String url, boolean scheduled) {
        Optional<PortalFetchStatus> status = fetchStatusRecorder != null ? fetchStatusRecorder.find(source) : Optional.empty();
        i.detail("Last fully successful import", status.map(PortalFetchStatus::getLastCompleteAt)
                .map(TIME::format).orElse("None recorded"));
        Optional<SyncRetryTracker.PendingRetry> next = retryTracker.find(source);
        if (scheduled && next.isPresent()) {
            i.detail("Next automatic retry", TIME.format(LocalDateTime.ofInstant(next.get().dueAt, IST))
                    + " (retry " + (next.get().attemptsUsed + 1) + " of " + retryTracker.getMaxAttempts() + ")");
        } else {
            i.detail("Next automatic retry", retryTracker.isEnabled()
                    ? "None left; waits for the next regular run" : "Retries are turned off");
        }
        i.detail("Portal", url);
    }

    private Incident recovered(String source, String name, SyncRetryTracker.PendingRetry before, int imported) {
        return new Incident(Event.RECOVERED, source, name, before.reason)
                .detail("Recovered at", TIME.format(LocalDateTime.now(IST)))
                .detail("Retries used", before.attemptsUsed + " of " + retryTracker.getMaxAttempts())
                .detail("New tenders imported by the retry", imported);
    }

    private static String describe(Throwable e) {
        return e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
    }
}
