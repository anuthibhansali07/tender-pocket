package com.tenderpocket.services;

import com.tenderpocket.models.SyncMarker;
import com.tenderpocket.repositories.SyncMarkerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * One-time recovery of tenders the sync missed before it was fixed: the email import read only the 5 tenders
 * printed in each Tender247 alert, and the GeM sync dropped bids whose wording did not match a keyword exactly.
 *
 * Runs once in the background after startup and only adds tenders. Its three steps are safe to repeat, so a
 * step that fails is retried on the next start; once all have finished, a marker stops it running again.
 */
@Component
public class MissedTenderCatchUp {

    static final String MARKER = "missed-tender-catch-up-2026-11";
    /** The catch-up never looks back further than this, whatever is configured. */
    static final int MAX_DAYS = 45;

    @Autowired
    private SyncMarkerRepository markers;

    @Autowired
    private EmailSyncService emailSyncService;

    @Autowired
    private GeMScraperService geMScraperService;

    @Value("${catchup.enabled:true}")
    boolean enabled = true;

    @Value("${catchup.days:45}")
    int days = MAX_DAYS;

    /** Tenders whose due date has passed cannot be bid on; by default they are not brought in. */
    @Value("${catchup.skip-expired:true}")
    boolean skipExpired = true;

    /** Pages of 10 results read per keyword on GeM during the catch-up. */
    @Value("${catchup.gem-max-pages:30}")
    int gemMaxPages = 30;

    long startDelayMs = 90_000;

    @EventListener(ApplicationReadyEvent.class)
    public void startInBackground() {
        if (!enabled) return;
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(startDelayMs);   // let the regular startup sync begin first
                run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[CatchUp] Stopped: " + e.getMessage());
            }
        }, "missed-tender-catch-up");
        worker.setDaemon(true);
        worker.start();
    }

    void run() {
        if (markers.existsById(MARKER)) return;
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        System.out.println(String.format(
                "[CatchUp] One-time catch-up of missed tenders started: last %d days, tenders past their due date are %s.",
                window, skipExpired ? "skipped" : "included"));
        boolean complete = true;
        StringBuilder summary = new StringBuilder("window=" + window + " days, skipExpired=" + skipExpired);

        // 1. The GeM bids clients reported missing, by bid number. First, because some close within days.
        try {
            int imported = geMScraperService.importBidsByNumber(reportedGemBids());
            summary.append("; GeM bids by number: ").append(imported);
        } catch (Exception e) {
            complete = false;
            System.err.println("[CatchUp] GeM bids by number failed: " + e.getMessage());
        }

        // 2. The full list of every Tender247 alert email in the window.
        try {
            int[] counts = emailSyncService.catchUpFromAlertEmails(window, skipExpired);
            summary.append(String.format("; Tender247 lists: %d read, %d imported, %d already present, %d past due, %d failed",
                    counts[0], counts[1], counts[2], counts[3], counts[4]));
            if (counts[4] > 0 && counts[0] == 0) complete = false;
        } catch (Exception e) {
            complete = false;
            System.err.println("[CatchUp] Tender247 alert lists failed: " + e.getMessage());
        }

        // 3. Every GeM keyword again, over open bids started in the window.
        try {
            int imported = geMScraperService.catchUp(window, gemMaxPages);
            summary.append("; GeM keywords: ").append(imported);
        } catch (Exception e) {
            complete = false;
            System.err.println("[CatchUp] GeM keyword catch-up failed: " + e.getMessage());
        }

        if (complete) {
            markers.save(new SyncMarker(MARKER, summary.toString()));
            System.out.println("[CatchUp] Finished and will not run again. " + summary);
        } else {
            System.out.println("[CatchUp] Not finished; the missing part is retried on the next start. " + summary);
        }
    }

    static List<String> reportedGemBids() throws Exception {
        try (var in = new ClassPathResource("catchup/missed-gem-bids.txt").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }
}
