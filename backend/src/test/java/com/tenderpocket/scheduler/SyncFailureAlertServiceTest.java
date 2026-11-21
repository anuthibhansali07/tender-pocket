package com.tenderpocket.scheduler;

import com.tenderpocket.scheduler.SyncFailureAlertService.Event;
import com.tenderpocket.scheduler.SyncFailureAlertService.Incident;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SyncFailureAlertServiceTest {

    private SyncFailureAlertService alerts;
    private final Instant now = Instant.parse("2026-11-16T01:00:00Z");

    @BeforeEach
    void setUp() {
        alerts = new SyncFailureAlertService();
        alerts.configure(true, 6, false);
    }

    @Test
    @DisplayName("The same portal failing for the same reason is not re-sent within the window")
    void repeatsAreSuppressed() {
        Incident first = new Incident(Event.FAILED, "MH", "Maharashtra", "HTTP 503");
        assertEquals(1, alerts.filter(List.of(first), now).size());
        assertTrue(alerts.filter(List.of(first), now.plus(Duration.ofHours(5))).isEmpty());
        assertEquals(1, alerts.filter(List.of(first), now.plus(Duration.ofHours(7))).size());
        // A different reason is new information.
        Incident other = new Incident(Event.FAILED, "MH", "Maharashtra", "Portal returned its Unauthorised Access page");
        assertEquals(1, alerts.filter(List.of(other), now.plus(Duration.ofHours(1))).size());
    }

    @Test
    @DisplayName("Intermediate retry failures are skipped; give-up and recovery are always sent")
    void retryEvents() {
        Incident retry = new Incident(Event.RETRY_FAILED, "UP", "Uttar Pradesh", "timeout");
        Incident gaveUp = new Incident(Event.GAVE_UP, "UP", "Uttar Pradesh", "timeout");
        Incident recovered = new Incident(Event.RECOVERED, "KA", "Karnataka", "timeout");
        List<Incident> sent = alerts.filter(List.of(retry, gaveUp, recovered), now);
        assertEquals(List.of(gaveUp, recovered), sent);

        alerts.configure(true, 6, true);
        assertEquals(1, alerts.filter(List.of(retry), now).size());
    }

    @Test
    @DisplayName("Nothing is sent when alerts are disabled")
    void disabled() {
        alerts.configure(false, 6, false);
        assertTrue(alerts.filter(List.of(new Incident(Event.FAILED, "GEM", "GeM", "x")), now).isEmpty());
    }

    @Test
    @DisplayName("The email names each failed source with its reason and details, HTML-escaped")
    void emailContent() {
        Incident i = new Incident(Event.FAILED, "CENTRAL", "Central Public Procurement Portal", "Unexpected <html> page")
                .detail("Next automatic retry", "16 Nov 2026, 08:15:00 AM IST (retry 1 of 3)")
                .detail("Department lists that failed", 4);
        String html = alerts.buildHtml("Daily state & central portal import",
                LocalDateTime.of(2026, 11, 16, 6, 0), LocalDateTime.of(2026, 11, 16, 6, 40), List.of(i));
        assertTrue(html.contains("Central Public Procurement Portal"));
        assertTrue(html.contains("Unexpected &lt;html&gt; page"));
        assertTrue(html.contains("retry 1 of 3"));
        assertTrue(html.contains("16 Nov 2026, 06:00:00 AM IST"));

        assertEquals("[TenderPocket] Failed: Daily state & central portal import - Central Public Procurement Portal",
                SyncFailureAlertService.subject("Daily state & central portal import", List.of(i)));
        assertTrue(SyncFailureAlertService.subject("x",
                List.of(new Incident(Event.GAVE_UP, "UP", "Uttar Pradesh", "t"))).contains("Still failing after retries"));
    }

    @Test
    @DisplayName("Sample alert email (this morning's GeM run) is written to target/sample-alert-email.html")
    void sampleEmail() throws Exception {
        Incident i = new Incident(Event.FAILED, "GEM", "GeM",
                "8 keyword search(es) failed even after re-login (GeM refused or timed out; often rate limiting)")
                .detail("Keywords searched", "All product keywords")
                .detail("Failed / not reached keywords", "Boyle's Machine, C-ARM Machine, X-Ray Machine, Freezer / Deep Freezer, "
                        + "Insulated Truck / Vehicles, Insulated Van / Vehicles, Solar Walk In Cooler and Freezer / Cold Room, Walk-In-Freezer / Cold Room")
                .detail("New tenders imported in this run", 36)
                .detail("Run started", "17 Nov 2026, 09:59:20 AM IST")
                .detail("Run finished", "17 Nov 2026, 10:28:38 AM IST")
                .detail("Duration", "29.3 min")
                .detail("Last fully successful import", "None recorded")
                .detail("Next automatic retry", "17 Nov 2026, 12:28:38 PM IST (retry 1 of 3)")
                .detail("Portal", "https://bidplus.gem.gov.in/all-bids");
        String html = alerts.buildHtml("Scheduled GeM import",
                LocalDateTime.of(2026, 11, 17, 9, 59, 20), LocalDateTime.of(2026, 11, 17, 10, 28, 38), List.of(i));
        assertTrue(html.contains("1 source failed"));
        assertTrue(html.contains("Boyle&#39;s Machine") || html.contains("Boyle's Machine"));
        assertTrue(html.contains("href=\"https://bidplus.gem.gov.in/all-bids\""));
        java.nio.file.Files.createDirectories(java.nio.file.Path.of("target"));
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "sample-alert-email.html"), html);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "sample-alert-email.subject.txt"),
                SyncFailureAlertService.subject("Scheduled GeM import", List.of(i)));
    }
}
