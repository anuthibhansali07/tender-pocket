package com.tenderpocket.services;

import com.tenderpocket.models.PortalFetchStatus;
import com.tenderpocket.repositories.PortalFetchStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class FetchStatusRecorderTest {

    private final Map<String, PortalFetchStatus> rows = new HashMap<>();
    private FetchStatusRecorder recorder;

    @BeforeEach
    void setUp() {
        PortalFetchStatusRepository repo = Mockito.mock(PortalFetchStatusRepository.class);
        Mockito.when(repo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.<String>getArgument(0))));
        Mockito.when(repo.save(any())).thenAnswer(inv -> {
            PortalFetchStatus s = inv.getArgument(0);
            rows.put(s.getSource(), s);
            return s;
        });
        recorder = new FetchStatusRecorder();
        ReflectionTestUtils.setField(recorder, "repository", repo);
    }

    @Test
    @DisplayName("A failed portal keeps its last successful time")
    void failureKeepsLastSuccess() {
        recorder.recordPortal("MH", 3, 0, null);
        var success = rows.get("MH").getLastCompleteAt();
        assertNotNull(success);
        recorder.recordPortal("MH", 0, 0, "HTTP 503");
        PortalFetchStatus s = rows.get("MH");
        assertEquals(PortalFetchStatus.FAILED, s.getOutcome());
        assertEquals(success, s.getLastCompleteAt());
        assertEquals(3, s.getNewTenders());
    }

    @Test
    @DisplayName("A portal read with some department lists failing is partial")
    void partial() {
        recorder.recordPortal("UP", 1, 2, null);
        assertEquals(PortalFetchStatus.PARTIAL, rows.get("UP").getOutcome());
        assertNull(rows.get("UP").getLastCompleteAt());
        assertNotNull(rows.get("UP").getLastReachedAt());
    }

    @Test
    @DisplayName("A GeM retry of the missed keywords clears only what it searched")
    void gemRetrySettlesSearchedKeywords() {
        recorder.recordGem(List.of(), List.of("wheelchair", "stretcher"), 5, false);
        assertEquals(PortalFetchStatus.PARTIAL, rows.get("GEM").getOutcome());

        recorder.recordGem(List.of("wheelchair"), List.of(), 1, false);
        assertEquals("stretcher", rows.get("GEM").getMissedKeywords());

        recorder.recordGem(List.of("stretcher"), List.of(), 0, false);
        assertEquals(PortalFetchStatus.OK, rows.get("GEM").getOutcome());
        assertNull(rows.get("GEM").getMissedKeywords());
    }

    @Test
    @DisplayName("A GeM run that crashes before finishing is a failure")
    void gemCrash() {
        recorder.recordGem(List.of(), List.of(), 0, true);
        assertEquals(PortalFetchStatus.FAILED, rows.get("GEM").getOutcome());
    }

    @Test
    @DisplayName("Tender247 with no alert inbox is 'not set up', not a failure, and never counts as read")
    void tender247NotSetUp() {
        recorder.recordTender247(false, 0, 0, false);
        PortalFetchStatus s = rows.get(FetchStatusRecorder.TENDER247);
        assertEquals(PortalFetchStatus.NOT_SET_UP, s.getOutcome());
        assertNull(s.getLastReachedAt());
        assertNull(s.getLastCompleteAt());
        assertNull(s.getNewTenders());
    }

    @Test
    @DisplayName("Tender247: unread alert emails make it partial; an inbox that won't open is a failure")
    void tender247Outcomes() {
        recorder.recordTender247(true, 4, 0, false);
        var complete = rows.get(FetchStatusRecorder.TENDER247).getLastCompleteAt();
        assertEquals(PortalFetchStatus.OK, rows.get(FetchStatusRecorder.TENDER247).getOutcome());

        recorder.recordTender247(true, 2, 1, false);
        PortalFetchStatus s = rows.get(FetchStatusRecorder.TENDER247);
        assertEquals(PortalFetchStatus.PARTIAL, s.getOutcome());
        assertEquals(1, s.getFailedSections());
        assertEquals(2, s.getNewTenders());

        recorder.recordTender247(true, 0, 0, true);
        s = rows.get(FetchStatusRecorder.TENDER247);
        assertEquals(PortalFetchStatus.FAILED, s.getOutcome());
        assertEquals(complete, s.getLastCompleteAt());
        assertEquals(2, s.getNewTenders(), "a failed run keeps the last known count");
    }
}
