package com.tenderpocket.scheduler;

import com.tenderpocket.services.AlertEngineService;
import com.tenderpocket.services.EmailSyncService;
import com.tenderpocket.services.GeMScraperService;
import com.tenderpocket.services.state.StateTenderSyncService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collections;

@Component
public class SyncScheduler {

    @Autowired
    private EmailSyncService emailSyncService;

    @Autowired
    private GeMScraperService geMScraperService;

    @Autowired
    private StateTenderSyncService stateTenderSyncService;

    // Run synchronization tasks on startup (15s after boot) and every 6 hours
    @Scheduled(initialDelay = 15000, fixedDelay = 21600000)
    public void runStartupSync() {
        System.out.println("[Scheduler] Running automated startup/periodic Tender sync...");
        performSync();
    }

    // State & central NIC eProcurement portals, daily (default 6:00 AM IST). Runs on its own
    // thread pool and returns immediately, so it never delays the GeM / Tender247 jobs below.
    @Scheduled(cron = "${state.sync.cron:0 0 6 * * *}", zone = "Asia/Kolkata")
    public void runStatePortalSync() {
        if (!stateTenderSyncService.isEnabled()) return;
        boolean started = stateTenderSyncService.startAsync(Collections.emptyList(), false);
        System.out.println(started
                ? "[Scheduler] State portal sync started in the background."
                : "[Scheduler] State portal sync already running; skipped this trigger.");
    }

    // Portals that only expose their newest few tenders without a CAPTCHA (Manipur lists 10) are
    // checked more often so a busy day does not push tenders out of view before the daily run.
    @Scheduled(cron = "${state.sync.frequent-cron:0 30 */3 * * *}", zone = "Asia/Kolkata")
    public void runFrequentStatePortalSync() {
        if (!stateTenderSyncService.isEnabled()) return;
        java.util.List<String> codes = stateTenderSyncService.getFrequentPortalCodes();
        if (codes.isEmpty()) return;
        boolean started = stateTenderSyncService.startAsync(codes, false);
        if (!started) System.out.println("[Scheduler] Frequent state portal check skipped: a sync is already running.");
    }

    // Run synchronization tasks daily at 8:00 AM IST
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Kolkata")
    public void runDailySync() {
        System.out.println("[Scheduler] Starting daily automated Tender synchronizations at 8:00 AM IST...");
        performSync();
    }

    private void performSync() {
        try {
            // 1. Sync emails from Tender247 (if credentials provided)
            try {
                int emailCount = emailSyncService.syncEmails();
                System.out.println("[Scheduler] Email Sync completed. Imported: " + emailCount);
            } catch (Exception e) {
                System.out.println("[Scheduler] Email sync skipped or failed (check IMAP credentials): " + e.getMessage());
            }

            // 2. Sync GeM portal bids (published in the last 2 days)
            try {
                int gemCount = geMScraperService.syncTenders(false, Collections.emptyList());
                System.out.println("[Scheduler] GeM Sync completed. Imported: " + gemCount);
            } catch (Exception e) {
                String errorMsg = e.getMessage() != null ? e.getMessage() : e.toString();
                System.err.println("[Scheduler] GeM Sync failed: " + errorMsg);
            }

        } catch (Exception e) {
            System.err.println("[Scheduler] Automated execution encountered an error: " + e.getMessage());
        }
    }
}
