package com.tenderpocket.scheduler;

import com.tenderpocket.models.PortalFetchStatus;
import com.tenderpocket.models.Tender;
import com.tenderpocket.services.FetchStatusRecorder;
import com.tenderpocket.services.GeMScraperService;
import com.tenderpocket.services.state.GepnicPortal;
import com.tenderpocket.services.state.StateTenderSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;

class SyncSchedulerTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 16, 10, 0);

    @Test
    @DisplayName("A restart imports only when GeM was never read or not read for 6 hours")
    void startupImportOnlyWhenStale() {
        assertTrue(SyncScheduler.shouldRunOnStartup(null, now, 6));
        assertTrue(SyncScheduler.shouldRunOnStartup(now.minusHours(7), now, 6));
        assertFalse(SyncScheduler.shouldRunOnStartup(now.minusHours(2), now, 6));
    }

    @Test
    @DisplayName("A second GeM sync is refused while one is running, and allowed again afterwards")
    void oneGemSyncAtATime() {
        GeMScraperService gem = new GeMScraperService();
        AtomicBoolean running = (AtomicBoolean) ReflectionTestUtils.getField(gem, "syncRunning");
        assertNotNull(running);
        running.set(true);
        assertTrue(gem.isSyncRunning());
        assertThrows(GeMScraperService.SyncAlreadyRunningException.class,
                () -> gem.syncTendersDetailed(false, List.of("wheelchair")));
        assertTrue(gem.isSyncRunning(), "the refused call must not release the running sync's lock");
    }

    private SyncScheduler schedulerWith(StateTenderSyncService state, GeMScraperService gem, FetchStatusRecorder recorder) {
        SyncScheduler s = new SyncScheduler();
        ReflectionTestUtils.setField(s, "stateTenderSyncService", state);
        ReflectionTestUtils.setField(s, "geMScraperService", gem);
        ReflectionTestUtils.setField(s, "fetchStatusRecorder", recorder);
        ReflectionTestUtils.setField(s, "retryTracker", new SyncRetryTracker());
        ReflectionTestUtils.setField(s, "alerts", Mockito.mock(SyncFailureAlertService.class));
        return s;
    }

    private StateTenderSyncService stateWithPortals() {
        StateTenderSyncService state = Mockito.mock(StateTenderSyncService.class);
        Mockito.when(state.isEnabled()).thenReturn(true);
        Mockito.when(state.getPortals()).thenReturn(List.of(
                new GepnicPortal("MH", "Maharashtra", "Maharashtra", "https://mahatenders.gov.in/nicgep/app", true, null),
                new GepnicPortal("UP", "Uttar Pradesh", "Uttar Pradesh", "https://etender.up.nic.in/nicgep/app", true, null)));
        Mockito.when(state.startAsync(any(), anyBoolean(), any())).thenReturn(true);
        return state;
    }

    @Test
    @DisplayName("Check again on a state runs only that state, then waits 30 minutes before another manual check")
    void manualStateRecheckRunsOnlyThatPortal() {
        StateTenderSyncService state = stateWithPortals();
        SyncScheduler s = schedulerWith(state, Mockito.mock(GeMScraperService.class), null);

        assertEquals(SyncScheduler.RecheckOutcome.STARTED, s.startManualRecheck("mh").outcome());
        Mockito.verify(state).startAsync(eq(List.of("MH")), eq(false), any());

        SyncScheduler.RecheckResult again = s.startManualRecheck("MH");
        assertEquals(SyncScheduler.RecheckOutcome.COOLDOWN, again.outcome());
        assertNotNull(again.availableAt());
        // Another portal is not blocked by MH's cooldown.
        assertEquals(SyncScheduler.RecheckOutcome.STARTED, s.startManualRecheck("UP").outcome());
        assertEquals(SyncScheduler.RecheckOutcome.UNKNOWN_SOURCE, s.startManualRecheck("XX").outcome());
    }

    @Test
    @DisplayName("Cooldown ends 30 minutes after the last manual check")
    void cooldownWindow() {
        assertNull(SyncScheduler.nextManualRecheckAt(null, 30));
        assertEquals(now.plusMinutes(30), SyncScheduler.nextManualRecheckAt(now, 30));
    }

    @Test
    @DisplayName("Check again on GeM searches only the products that were missed")
    void manualGemRecheckUsesMissedKeywords() throws Exception {
        GeMScraperService gem = Mockito.mock(GeMScraperService.class);
        Mockito.when(gem.syncTendersDetailed(anyBoolean(), any()))
                .thenReturn(Mockito.mock(GeMScraperService.SyncResult.class));
        FetchStatusRecorder recorder = Mockito.mock(FetchStatusRecorder.class);
        PortalFetchStatus status = new PortalFetchStatus("GEM");
        status.setOutcome(PortalFetchStatus.PARTIAL);
        status.setMissedKeywords("wheelchair,stretcher");
        Mockito.when(recorder.find("GEM")).thenReturn(Optional.of(status));

        SyncScheduler s = schedulerWith(stateWithPortals(), gem, recorder);
        SyncScheduler.RecheckResult r = s.startManualRecheck("GEM");
        assertEquals(SyncScheduler.RecheckOutcome.STARTED, r.outcome());
        assertEquals(2, r.keywordCount());
        Mockito.verify(gem, Mockito.timeout(2000)).syncTendersDetailed(false, List.of("wheelchair", "stretcher"));
    }

    @Test
    @DisplayName("Check again on GeM is refused while GeM is already being updated")
    void manualGemRecheckWhileRunning() {
        GeMScraperService gem = Mockito.mock(GeMScraperService.class);
        Mockito.when(gem.isSyncRunning()).thenReturn(true);
        SyncScheduler s = schedulerWith(stateWithPortals(), gem, null);
        assertEquals(SyncScheduler.RecheckOutcome.ALREADY_RUNNING, s.startManualRecheck("GEM").outcome());
    }

    @Test
    @DisplayName("A known GeM tender with its PDF already attached is not downloaded again")
    void pdfOnlyWhenMissing() throws Exception {
        var needsPdf = GeMScraperService.class.getDeclaredMethod("needsGemPdf", Tender.class);
        needsPdf.setAccessible(true);
        Tender t = new Tender();
        assertTrue((boolean) needsPdf.invoke(null, t));
        t.setDocumentUrl("/documents/10165330/Bid_Document_10165330.pdf");
        assertFalse((boolean) needsPdf.invoke(null, t));
    }

    @Test
    @DisplayName("Check again on Tender247 says 'not connected' when no alert inbox is set up")
    void manualTender247NotSetUp() {
        com.tenderpocket.services.EmailSyncService email = Mockito.mock(com.tenderpocket.services.EmailSyncService.class);
        Mockito.when(email.isConfigured()).thenReturn(false);
        SyncScheduler s = schedulerWith(stateWithPortals(), Mockito.mock(GeMScraperService.class), null);
        ReflectionTestUtils.setField(s, "emailSyncService", email);
        assertEquals(SyncScheduler.RecheckOutcome.NOT_SET_UP, s.startManualRecheck("tender247").outcome());
    }

    @Test
    @DisplayName("Check again on Tender247 reads only the inbox, then waits 30 minutes")
    void manualTender247Recheck() throws Exception {
        com.tenderpocket.services.EmailSyncService email = Mockito.mock(com.tenderpocket.services.EmailSyncService.class);
        Mockito.when(email.isConfigured()).thenReturn(true);
        Mockito.when(email.syncEmailsDetailed()).thenReturn(Mockito.mock(com.tenderpocket.services.EmailSyncService.SyncResult.class));
        StateTenderSyncService state = stateWithPortals();
        GeMScraperService gem = Mockito.mock(GeMScraperService.class);
        SyncScheduler s = schedulerWith(state, gem, null);
        ReflectionTestUtils.setField(s, "emailSyncService", email);

        assertEquals(SyncScheduler.RecheckOutcome.STARTED, s.startManualRecheck("TENDER247").outcome());
        Mockito.verify(email, Mockito.timeout(2000)).syncEmailsDetailed();
        Mockito.verify(gem, Mockito.never()).syncTendersDetailed(anyBoolean(), any());
        Mockito.verify(state, Mockito.never()).startAsync(any(), anyBoolean(), any());
        assertEquals(SyncScheduler.RecheckOutcome.COOLDOWN, s.startManualRecheck("TENDER247").outcome());
    }

    @Test
    @DisplayName("Check again on Tender247 is refused while the inbox is already being read")
    void manualTender247WhileRunning() {
        com.tenderpocket.services.EmailSyncService email = Mockito.mock(com.tenderpocket.services.EmailSyncService.class);
        Mockito.when(email.isConfigured()).thenReturn(true);
        Mockito.when(email.isSyncRunning()).thenReturn(true);
        SyncScheduler s = schedulerWith(stateWithPortals(), Mockito.mock(GeMScraperService.class), null);
        ReflectionTestUtils.setField(s, "emailSyncService", email);
        assertEquals(SyncScheduler.RecheckOutcome.ALREADY_RUNNING, s.startManualRecheck("TENDER247").outcome());
    }

    @Test
    @DisplayName("At startup, only state portals not checked in the last 20 hours are checked")
    void startupStateCatchUpOnlyStalePortals() {
        StateTenderSyncService state = stateWithPortals();
        FetchStatusRecorder recorder = Mockito.mock(FetchStatusRecorder.class);
        PortalFetchStatus mh = new PortalFetchStatus("MH");
        mh.setLastAttemptAt(LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).minusHours(1));
        Mockito.when(recorder.find("MH")).thenReturn(Optional.of(mh));
        Mockito.when(recorder.find("UP")).thenReturn(Optional.empty());
        SyncScheduler s = schedulerWith(state, Mockito.mock(GeMScraperService.class), recorder);

        s.runStartupStateCatchUp();
        Mockito.verify(state).startAsync(eq(List.of("UP")), eq(false), any());

        assertTrue(SyncScheduler.isStale(null, now, 20));
        assertTrue(SyncScheduler.isStale(now.minusHours(21), now, 20));
        assertFalse(SyncScheduler.isStale(now.minusHours(3), now, 20));
    }
}
