package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenderpocket.models.SyncMarker;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.SyncMarkerRepository;
import com.tenderpocket.repositories.TenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Tenders that came from Tender247 with a GeM bid number get their official GeM link by a lookup on GeM. */
class GemLinkLookupTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Tender> tenders = new LinkedHashMap<>();
    private final Map<String, SyncMarker> markers = new HashMap<>();
    /** Bid numbers GeM lists as open. */
    private final List<String> openOnGem = new ArrayList<>();
    private final List<String> lookedUp = new ArrayList<>();
    private boolean gemDown;

    private GeMScraperService service;
    private final String nextMonth = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(30).toString();
    private final String lastMonth = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(30).toString();

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new GeMScraperService());

        TenderRepository tenderRepository = mock(TenderRepository.class);
        lenient().when(tenderRepository.findGemTendersWithoutGemLink()).thenAnswer(call -> {
            List<TenderRepository.GemLinkCandidate> list = new ArrayList<>();
            for (Tender t : tenders.values()) {
                if (t.getOriginalUrl() != null && t.getOriginalUrl().contains("gem.gov.in")) continue;
                list.add(new TenderRepository.GemLinkCandidate() {
                    public String getId() { return t.getId(); }
                    public String getRefNo() { return t.getRefNo(); }
                    public String getDueDate() { return t.getDueDate(); }
                });
            }
            return list;
        });
        lenient().when(tenderRepository.findById(anyString()))
                .thenAnswer(call -> Optional.ofNullable(tenders.get(call.<String>getArgument(0))));
        lenient().when(tenderRepository.save(any(Tender.class))).thenAnswer(call -> call.getArgument(0));

        SyncMarkerRepository markerRepository = mock(SyncMarkerRepository.class);
        lenient().when(markerRepository.findByNameStartingWith(anyString())).thenAnswer(call -> {
            List<SyncMarker> list = new ArrayList<>();
            for (SyncMarker m : markers.values()) if (m.getName().startsWith(call.<String>getArgument(0))) list.add(m);
            return list;
        });
        lenient().when(markerRepository.save(any(SyncMarker.class))).thenAnswer(call -> {
            SyncMarker marker = call.getArgument(0);
            markers.put(marker.getName(), marker);
            return marker;
        });

        ReflectionTestUtils.setField(service, "tenderRepository", tenderRepository);
        ReflectionTestUtils.setField(service, "syncMarkerRepository", markerRepository);
        ReflectionTestUtils.setField(service, "firstPageIndex", 1);
        ReflectionTestUtils.setField(service, "requestPauseMs", 0L);

        doAnswer(call -> {
            String number = call.getArgument(0);
            lookedUp.add(number);
            if (gemDown) return null;
            ArrayNode docs = mapper.createArrayNode();
            if (openOnGem.contains(number)) {
                ObjectNode doc = mapper.createObjectNode();
                doc.put("b_id", number.substring(number.lastIndexOf('/') + 1));
                doc.put("b_bid_number", number);
                doc.put("b_category_name", "Infant Warmer (V2)");
                doc.put("ba_official_details_deptName", "Health Department");
                doc.put("final_start_date_sort", "2026-11-23T10:00:00Z");
                doc.put("final_end_date_sort", "2026-12-20T15:00:00Z");
                docs.add(doc);
            }
            return (JsonNode) docs;
        }).when(service).searchKeywordWithRetry(anyString(), anyInt());
    }

    /** A tender as the Tender247 email import saves it. The document is set so the test downloads nothing. */
    private Tender fromTender247(String id, String refNo, String dueDate) {
        Tender t = new Tender();
        t.setId(id);
        t.setRefNo(refNo);
        t.setSource("Tender247");
        t.setSourceId(id);
        t.setDueDate(dueDate);
        t.setOriginalUrl("https://www.tender247.com/auth/tender/" + id);
        t.setDocumentUrl("existing/doc.pdf");
        tenders.put(id, t);
        return t;
    }

    @Test
    @DisplayName("A Tender247 tender with a GeM bid number gets the official GeM link, once")
    void tender247TenderIsLinkedOnce() {
        Tender t = fromTender247("105984831", "GEM/2026/B/8294939", nextMonth);
        openOnGem.add("GEM/2026/B/8294939");

        assertEquals(1, service.linkTendersToGem(100));
        assertEquals("https://bidplus.gem.gov.in/showbidDocument/8294939", t.getOriginalUrl());
        assertEquals("linked", markers.get("gem-link:GEM/2026/B/8294939").getDetails());

        lookedUp.clear();
        assertEquals(0, service.linkTendersToGem(100));
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    @DisplayName("A bid GeM no longer lists is recorded and not looked up again")
    void bidNotOnGemIsLookedUpOnce() {
        Tender t = fromTender247("105000001", "GEM/2026/B/8100001", nextMonth);

        assertEquals(0, service.linkTendersToGem(100));
        assertTrue(t.getOriginalUrl().contains("tender247.com"));

        lookedUp.clear();
        service.linkTendersToGem(100);
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    @DisplayName("Tenders past their due date are not looked up")
    void pastDueTendersAreLeftAlone() {
        fromTender247("105000002", "GEM/2026/B/8100002", lastMonth);
        openOnGem.add("GEM/2026/B/8100002");

        assertEquals(0, service.linkTendersToGem(100));
        assertTrue(lookedUp.isEmpty());
    }

    @Test
    @DisplayName("At most the limit is looked up per sync, newest bids first; the rest wait for the next sync")
    void limitPerSyncNewestFirst() {
        for (int i = 1; i <= 5; i++) {
            fromTender247("10500001" + i, "GEM/2026/B/810001" + i, nextMonth);
            openOnGem.add("GEM/2026/B/810001" + i);
        }

        assertEquals(2, service.linkTendersToGem(2));
        assertEquals(List.of("GEM/2026/B/8100015", "GEM/2026/B/8100014"), lookedUp);

        lookedUp.clear();
        assertEquals(3, service.linkTendersToGem(100));
        assertEquals(List.of("GEM/2026/B/8100013", "GEM/2026/B/8100012", "GEM/2026/B/8100011"), lookedUp);
    }

    @Test
    @DisplayName("A failed lookup is not recorded, and the step gives up after three failures in a row")
    void failedLookupsAreRetriedNextSync() {
        for (int i = 1; i <= 6; i++) {
            fromTender247("10500002" + i, "GEM/2026/B/810002" + i, nextMonth);
            openOnGem.add("GEM/2026/B/810002" + i);
        }
        gemDown = true;

        assertEquals(0, service.linkTendersToGem(100));
        assertEquals(3, lookedUp.size());
        assertTrue(markers.isEmpty());

        gemDown = false;
        assertEquals(6, service.linkTendersToGem(100));
    }

    @Test
    @DisplayName("A limit of 0 turns the step off")
    void zeroTurnsItOff() {
        fromTender247("105000003", "GEM/2026/B/8100003", nextMonth);
        openOnGem.add("GEM/2026/B/8100003");

        assertEquals(0, service.linkTendersToGem(0));
        assertTrue(lookedUp.isEmpty());
    }
}
