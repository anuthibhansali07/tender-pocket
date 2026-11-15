package com.tenderpocket.services.state;

import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StateTenderSyncServiceTest {

    private static final GepnicPortal MH = new GepnicPortal("MH", "Maharashtra", "Maharashtra",
            "https://mahatenders.gov.in/nicgep/app", true, null);
    private static final String ORG_URL = "https://mahatenders.gov.in/nicgep/app?component=%24DirectLink&page=FrontEndTendersByOrganisation&sp=dhs";

    @Mock
    private TenderRepository tenderRepository;

    private StateTenderSyncService service;
    private FixtureFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        service = new StateTenderSyncService();
        ReflectionTestUtils.setField(service, "tenderRepository", tenderRepository);
        // The fixtures are from 2026-11-12; a very long window keeps the test date-independent.
        ReflectionTestUtils.setField(service, "lookbackDays", 100_000);
        ReflectionTestUtils.setField(service, "maxDetailsPerPortal", 400);
        when(tenderRepository.findFirstBySourceId(anyString())).thenReturn(Optional.empty());
        when(tenderRepository.findFirstByRefNo(anyString())).thenReturn(Optional.empty());
        when(tenderRepository.save(any(Tender.class))).thenAnswer(inv -> inv.getArgument(0));
        fetcher = new FixtureFetcher();
    }

    @Test
    @DisplayName("Imports relevant health-department equipment tenders and skips medicines")
    void importsRelevantTenders() {
        StateTenderSyncService.PortalResult result = service.syncPortal(MH, fetcher, false);

        assertNull(result.error);
        assertEquals(1, result.organisations);
        assertEquals(17, result.rowsSeen);
        assertTrue(result.imported >= 2, "expected the equipment tenders to be imported, got " + result.imported);
        assertEquals(result.imported, result.relevant);

        ArgumentCaptor<Tender> saved = ArgumentCaptor.forClass(Tender.class);
        verify(tenderRepository, times(result.imported)).save(saved.capture());
        List<Tender> tenders = saved.getAllValues();
        assertTrue(tenders.stream().noneMatch(t -> t.getTitle().toLowerCase().contains("medicine")),
                "medicine purchases are not our products");

        Tender bloodBank = tenders.stream().filter(t -> "2026_DHS_1352679_1".equals(t.getRefNo())).findFirst().orElseThrow();
        assertEquals("gep-mh-2026_dhs_1352679_1", bloodBank.getId());
        assertEquals("GePNIC-MH", bloodBank.getSource());
        assertEquals("2026_DHS_1352679_1", bloodBank.getSourceId());
        assertEquals("2026_DHS_1352679_1", bloodBank.getEprocurementPortalId());
        assertEquals("E-5/GHAmt/DPDC/Blood Bank/Equip/2026-27", bloodBank.getOfficialReferenceNumber());
        assertEquals("Non GeM", bloodBank.getTenderType());
        assertEquals("Maharashtra", bloodBank.getState());
        assertEquals("District General Hospital Amravati", bloodBank.getPlace());
        assertEquals("District General Hospital Amravati, Maharashtra", bloodBank.getLocation());
        assertEquals(494000.0, bloodBank.getEmd());
        assertEquals(10000.0, bloodBank.getDocumentFee());
        assertNull(bloodBank.getEstimatedCost());
        assertEquals("2026-11-21 18:00:00", bloodBank.getDueDate());
        assertEquals("2026-11-06", bloodBank.getPublishDate());
        assertEquals("16-Nov-2026 12:00 PM", bloodBank.getPreBidDate());
        assertEquals("Issued", bloodBank.getStatus());
        assertEquals("mahatenders.gov.in", bloodBank.getSector());
        assertTrue(bloodBank.getOriginalUrl().contains("FrontEndTendersByOrganisation"));
        assertTrue(bloodBank.getNotes().contains("Product category: Medical Equipments/Waste"));
        assertTrue(bloodBank.getNotes().contains("Pincode: 444602"));
    }

    @Test
    @DisplayName("Dry run reports what it would import and writes nothing")
    void dryRunWritesNothing() {
        StateTenderSyncService.PortalResult result = service.syncPortal(MH, fetcher, true);

        assertTrue(result.imported >= 2);
        assertFalse(result.sample.isEmpty());
        verify(tenderRepository, never()).save(any());
    }

    @Test
    @DisplayName("A Tender247 record with the same Tender ID is enriched, not duplicated")
    void enrichesExistingTender247Record() {
        Tender t247 = new Tender();
        t247.setId("t247-99887766");
        t247.setSource("Tender247");
        t247.setSourceId("99887766");
        t247.setRefNo("2026_DHS_1352679_1");
        t247.setTitle("Blood bank equipment");
        t247.setTenderType("Non GeM");
        when(tenderRepository.findFirstByRefNo("2026_DHS_1352679_1")).thenReturn(Optional.of(t247));

        StateTenderSyncService.PortalResult result = service.syncPortal(MH, fetcher, false);

        assertEquals(1, result.enriched);
        assertEquals("Tender247", t247.getSource(), "the original source is kept");
        assertEquals("2026_DHS_1352679_1", t247.getEprocurementPortalId());
        assertEquals("E-5/GHAmt/DPDC/Blood Bank/Equip/2026-27", t247.getOfficialReferenceNumber());
        assertEquals("Maharashtra", t247.getState());
        assertEquals(494000.0, t247.getEmd());
        assertTrue(t247.getNotes().contains("Matched on Maharashtra portal as Tender ID 2026_DHS_1352679_1"));
        verify(tenderRepository, never()).save(argThatHasId("gep-mh-2026_dhs_1352679_1"));
    }

    @Test
    @DisplayName("Tenders already imported from the same portal are skipped without fetching details")
    void skipsKnownTenders() {
        Tender known = new Tender();
        known.setSource("GePNIC-MH");
        when(tenderRepository.findFirstBySourceId(anyString())).thenReturn(Optional.of(known));

        StateTenderSyncService.PortalResult result = service.syncPortal(MH, fetcher, false);

        assertEquals(0, result.imported);
        assertEquals(result.relevant, result.alreadyKnown);
        assertEquals(0, result.detailsFetched);
        verify(tenderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Rows published before the lookback window are ignored")
    void respectsLookbackWindow() {
        ReflectionTestUtils.setField(service, "lookbackDays", 1);
        // Every fixture row is far older than one day before the real current date in normal runs.
        if (LocalDateTime.now(StateTenderSyncService.IST).isAfter(LocalDateTime.of(2026, 12, 31, 0, 0))) {
            StateTenderSyncService.PortalResult result = service.syncPortal(MH, fetcher, false);
            assertEquals(0, result.rowsInWindow);
            verify(tenderRepository, never()).save(any());
        }
    }

    @Test
    @DisplayName("A blocked portal reports an error instead of crashing the run")
    void blockedPortalReportsError() {
        PageFetcher blocked = new PageFetcher() {
            @Override public Document get(String url) throws GepnicSession.PortalBlockedException {
                throw new GepnicSession.PortalBlockedException("Portal returned its Unauthorised Access page");
            }
            @Override public int requestCount() { return 1; }
        };
        StateTenderSyncService.PortalResult result = service.syncPortal(MH, blocked, false);
        assertNotNull(result.error);
        assertTrue(result.error.contains("Unauthorised"));
    }

    @Test
    void relevanceRules() {
        assertNotNull(StateTenderSyncService.relevanceReason(row("Purchase of ICU Bed and Fowler Bed", "PWD||Division 1")));
        assertNotNull(StateTenderSyncService.relevanceReason(row("Supply of Equipment and Instrument for District Hospital",
                "Directorate of Health Services||District Hospital")));
        assertNull(StateTenderSyncService.relevanceReason(row("Purchase of Medicines and Consumables",
                "Directorate of Health Services||District Hospital")));
        assertNull(StateTenderSyncService.relevanceReason(row("Construction of road from A to B", "PWD||Division 1")));
        assertNull(StateTenderSyncService.relevanceReason(row("Supply of machines for road repair", "PWD||Division 1")),
                "generic goods words only count inside health departments");
    }

    private static GepnicParser.TenderRow row(String title, String org) {
        return new GepnicParser.TenderRow("2026_X_1_1", title, "REF/1", org, null, null, null, null);
    }

    private static Tender argThatHasId(String id) {
        return org.mockito.ArgumentMatchers.argThat(t -> t != null && id.equals(t.getId()));
    }

    /** Serves the saved Maharashtra pages: one department, its list, and one detail page. */
    private static class FixtureFetcher implements PageFetcher {
        private int requests;
        private final Document list;
        private final String bloodBankDetailUrl;

        FixtureFetcher() throws Exception {
            list = GepnicParserTest.fixture("mahatenders-org-dhs.html", GepnicParserTest.BASE);
            bloodBankDetailUrl = GepnicParser.parseTenderList(list).stream()
                    .filter(r -> r.tenderId().equals("2026_DHS_1352679_1")).findFirst().orElseThrow().detailUrl();
        }

        @Override
        public Document get(String url) throws java.io.IOException {
            requests++;
            try {
                if (url.endsWith("page=FrontEndTendersByOrganisation&service=page")) {
                    return Jsoup.parse("<table><tr><td>1</td><td>Directorate of Health Services, Mumbai</td>"
                            + "<td><a href=\"" + ORG_URL + "\">20</a></td></tr></table>", GepnicParserTest.BASE);
                }
                if (url.equals(ORG_URL)) return list;
                if (url.equals(bloodBankDetailUrl)) {
                    return GepnicParserTest.fixture("mahatenders-detail-2026_DHS_1352679_1.html", GepnicParserTest.BASE);
                }
                return Jsoup.parse("<html><body>detail not saved</body></html>", url);
            } catch (java.io.IOException e) {
                throw e;
            } catch (Exception e) {
                throw new java.io.IOException(e);
            }
        }

        @Override
        public int requestCount() {
            return requests;
        }
    }
}
