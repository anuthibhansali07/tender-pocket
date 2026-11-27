package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenderpocket.models.SyncMarker;
import com.tenderpocket.repositories.SyncMarkerRepository;
import com.tenderpocket.repositories.TenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** A keyword search continues from its last successful search instead of reading back to yesterday every time. */
class GemSearchResumeTest {

    private static final List<String> KEYWORDS = List.of("Zebra Crossing");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, SyncMarker> markers = new HashMap<>();
    private final List<Integer> pagesAsked = new ArrayList<>();
    /** GeM's answer: the bids of a search, newest first. */
    private List<ObjectNode> gemBids = new ArrayList<>();
    private boolean failSearch;

    private GeMScraperService service;
    private SyncMarkerRepository markerRepository;
    private LocalDate cutoff;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new GeMScraperService());
        TenderRepository tenderRepository = mock(TenderRepository.class);
        lenient().when(tenderRepository.findFirstByRefNo(anyString())).thenReturn(Optional.empty());
        lenient().when(tenderRepository.findById(anyString())).thenReturn(Optional.empty());

        markerRepository = mock(SyncMarkerRepository.class);
        lenient().when(markerRepository.findByNameStartingWith(anyString()))
                .thenAnswer(call -> new ArrayList<>(markers.values()));
        lenient().when(markerRepository.save(any(SyncMarker.class))).thenAnswer(call -> {
            SyncMarker marker = call.getArgument(0);
            markers.put(marker.getName(), marker);
            return marker;
        });
        lenient().doAnswer(call -> {
            for (SyncMarker marker : call.<List<SyncMarker>>getArgument(0)) markers.remove(marker.getName());
            return null;
        }).when(markerRepository).deleteAll(any());

        ReflectionTestUtils.setField(service, "tenderRepository", tenderRepository);
        ReflectionTestUtils.setField(service, "syncMarkerRepository", markerRepository);
        ReflectionTestUtils.setField(service, "firstPageIndex", 1);
        ReflectionTestUtils.setField(service, "stopAfterKnown", 20);
        ReflectionTestUtils.setField(service, "requestPauseMs", 0L);
        ReflectionTestUtils.setField(service, "resumeOverlapMinutes", 180L);

        doAnswer(call -> {
            int page = call.getArgument(1);
            pagesAsked.add(page);
            if (failSearch) return null;
            ArrayNode docs = mapper.createArrayNode();
            for (int i = (page - 1) * 10; i < Math.min(page * 10, gemBids.size()); i++) docs.add(gemBids.get(i));
            return (JsonNode) docs;
        }).when(service).searchKeywordWithRetry(anyString(), anyInt());

        // The date cutoff of the regular sync is the start of yesterday; these tests keep every bid inside it.
        cutoff = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(3);
    }

    /** Bids that no keyword matches, so nothing is imported: number {@code first} is the newest. */
    private List<ObjectNode> bids(int first, int count, Instant newest, Duration apart) {
        List<ObjectNode> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode doc = mapper.createObjectNode();
            doc.put("b_id", String.valueOf(900000 + first + i));
            doc.put("b_bid_number", "GEM/2026/B/" + (900000 + first + i));
            doc.put("b_category_name", "Unrelated item " + (first + i));
            doc.put("final_start_date_sort", newest.minus(apart.multipliedBy(i)).toString());
            list.add(doc);
        }
        return list;
    }

    private int sync(boolean resume) throws Exception {
        pagesAsked.clear();
        return service.runSync(KEYWORDS, KEYWORDS, KEYWORDS, cutoff, 15, true, resume);
    }

    private void setLastSearch(Instant when) {
        String name = GemSearchProgress.markerName(GemSearchProgress.fingerprint(KEYWORDS, KEYWORDS), "Zebra Crossing");
        markers.put(name, new SyncMarker(name, when.toString()));
    }

    @Test
    @DisplayName("The first sync reads every page and records when the keyword was searched")
    void firstSyncReadsEverythingAndRecordsTheSearch() throws Exception {
        gemBids = bids(0, 34, Instant.now().minus(Duration.ofMinutes(5)), Duration.ofMinutes(20));

        Instant before = Instant.now();
        sync(true);

        assertEquals(List.of(1, 2, 3, 4), pagesAsked);
        assertEquals(1, markers.size());
        Instant recorded = Instant.parse(markers.values().iterator().next().getDetails());
        assertFalse(recorded.isBefore(before));
    }

    @Test
    @DisplayName("The next sync stops at bids the last search already read")
    void nextSyncStopsAtBidsAlreadyRead() throws Exception {
        // Last searched an hour ago. Two bids are newer; the rest started more than three hours before that search.
        setLastSearch(Instant.now().minus(Duration.ofHours(1)));
        List<ObjectNode> list = bids(0, 2, Instant.now().minus(Duration.ofMinutes(10)), Duration.ofMinutes(5));
        list.addAll(bids(100, 32, Instant.now().minus(Duration.ofHours(5)), Duration.ofMinutes(20)));
        gemBids = list;

        sync(true);

        assertEquals(List.of(1), pagesAsked);
    }

    @Test
    @DisplayName("Bids inside the overlap before the last search are read again, not skipped")
    void bidsInsideTheOverlapAreReadAgain() throws Exception {
        setLastSearch(Instant.now().minus(Duration.ofHours(1)));
        // 14 bids, all started within three hours before the last search: GeM may have listed them late.
        gemBids = bids(0, 14, Instant.now().minus(Duration.ofMinutes(90)), Duration.ofMinutes(10));

        sync(true);

        assertEquals(List.of(1, 2), pagesAsked);
    }

    @Test
    @DisplayName("A failed search is not recorded, so the next sync starts from the earlier record")
    void failedSearchIsNotRecorded() throws Exception {
        Instant earlier = Instant.now().minus(Duration.ofHours(7));
        setLastSearch(earlier);
        failSearch = true;

        sync(true);

        assertEquals(earlier.toString(), markers.values().iterator().next().getDetails());
    }

    @Test
    @DisplayName("A search cut short by the page limit is not recorded")
    void searchCutShortByThePageLimitIsNotRecorded() throws Exception {
        gemBids = bids(0, 200, Instant.now().minus(Duration.ofMinutes(5)), Duration.ofMinutes(1));

        sync(true);

        assertEquals(15, pagesAsked.size());
        assertTrue(markers.isEmpty());
    }

    @Test
    @DisplayName("When the keyword list changes, old records are dropped and the keyword is read in full")
    void changedKeywordListStartsAgain() throws Exception {
        String old = GemSearchProgress.markerName(GemSearchProgress.fingerprint(List.of("Something Else"), KEYWORDS), "Zebra Crossing");
        markers.put(old, new SyncMarker(old, Instant.now().minus(Duration.ofHours(1)).toString()));
        gemBids = bids(100, 24, Instant.now().minus(Duration.ofHours(5)), Duration.ofMinutes(20));

        sync(true);

        assertEquals(List.of(1, 2, 3), pagesAsked);
        assertFalse(markers.containsKey(old));
        assertEquals(1, markers.size());
    }

    @Test
    @DisplayName("The catch-up and custom syncs neither use nor record the last search")
    void otherSyncsIgnoreTheRecord() throws Exception {
        setLastSearch(Instant.now().minus(Duration.ofHours(1)));
        String before = markers.values().iterator().next().getDetails();
        gemBids = bids(100, 24, Instant.now().minus(Duration.ofHours(5)), Duration.ofMinutes(20));

        sync(false);

        assertEquals(List.of(1, 2, 3), pagesAsked);
        assertEquals(before, markers.values().iterator().next().getDetails());
        verify(markerRepository, never()).save(any(SyncMarker.class));
    }

    @Test
    @DisplayName("Bid start times: a date without a time counts as the end of that day")
    void bidStartTimes() {
        assertEquals(Instant.parse("2026-11-26T04:30:00Z"), GemSearchProgress.bidStart("2026-11-26T04:30:00Z"));
        assertEquals(Instant.parse("2026-11-26T04:30:00Z"), GemSearchProgress.bidStart("2026-11-26T10:00:00"));
        assertEquals(Instant.parse("2026-11-26T18:30:00Z"), GemSearchProgress.bidStart("2026-11-26"));
        assertNull(GemSearchProgress.bidStart("soon"));
        assertNull(GemSearchProgress.bidStart(null));
        assertFalse(GemSearchProgress.coveredByLastSearch(null, Instant.now(), Duration.ofHours(3)));
    }
}
