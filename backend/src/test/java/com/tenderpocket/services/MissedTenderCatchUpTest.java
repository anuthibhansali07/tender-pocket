package com.tenderpocket.services;

import com.tenderpocket.models.SyncMarker;
import com.tenderpocket.repositories.SyncMarkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MissedTenderCatchUpTest {

    private SyncMarkerRepository markers;
    private EmailSyncService emailSync;
    private GeMScraperService gemSync;
    private MissedTenderCatchUp catchUp;

    @BeforeEach
    void setUp() throws Exception {
        markers = mock(SyncMarkerRepository.class);
        emailSync = mock(EmailSyncService.class);
        gemSync = mock(GeMScraperService.class);
        catchUp = new MissedTenderCatchUp();
        ReflectionTestUtils.setField(catchUp, "markers", markers);
        ReflectionTestUtils.setField(catchUp, "emailSyncService", emailSync);
        ReflectionTestUtils.setField(catchUp, "geMScraperService", gemSync);
        when(emailSync.catchUpFromAlertEmails(anyInt(), anyBoolean())).thenReturn(new int[]{200, 900, 4000, 5000, 0});
    }

    @Test
    void runsTheThreeStepsOnceAndRecordsIt() throws Exception {
        catchUp.run();

        verify(gemSync).importBidsByNumber(argThat(bids -> bids.size() == 43 && bids.contains("GEM/2026/B/8273521")));
        verify(emailSync).catchUpFromAlertEmails(45, true);
        verify(gemSync).catchUp(45, 30);
        verify(markers).save(argThat((SyncMarker m) -> MissedTenderCatchUp.MARKER.equals(m.getName())));
    }

    @Test
    void doesNothingOnceTheMarkerExists() throws Exception {
        when(markers.existsById(MissedTenderCatchUp.MARKER)).thenReturn(true);

        catchUp.run();

        verifyNoInteractions(emailSync, gemSync);
    }

    @Test
    void neverLooksBackFurtherThanFortyFiveDays() throws Exception {
        catchUp.days = 120;

        catchUp.run();

        verify(emailSync).catchUpFromAlertEmails(45, true);
        verify(gemSync).catchUp(45, 30);
    }

    @Test
    void aFailedStepLeavesTheCatchUpToBeRetried() throws Exception {
        when(emailSync.catchUpFromAlertEmails(anyInt(), anyBoolean())).thenThrow(new IllegalStateException("IMAP down"));

        catchUp.run();

        verify(gemSync).catchUp(45, 30);            // the other steps still run
        verify(markers, never()).save(any());       // but it is not recorded as done
    }
}
