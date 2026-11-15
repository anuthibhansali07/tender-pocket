package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/** Parsing and request building for the non-NIC state portals, from responses seen on 2026-11-12. */
class StatePortalCrawlersTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---- Andhra Pradesh / Telangana ----

    @Test
    void parsesTelanganaTenColumnRow() throws Exception {
        JsonNode row = mapper.readTree("[\"TELANGANA STATE MEDICAL SERVICES-HQ\",\"747415\",\"TGMSIDC/EQP/2026\",\"PRODUCTS\","
                + "\"Procurement and Supply of Ophthalmology Equipment\",\"118170000.00\",\"12/11/2026 07:12 PM\","
                + "\"13/11/2026 10:00 AM\",\"27/11/2026 05:00 PM\",\"<a href='#'>view</a>\"]");
        PortalListCrawler.ListedTender t = ApTsEprocCrawler.parseRow(row);
        assertEquals("747415", t.tenderId());
        assertEquals("TGMSIDC/EQP/2026", t.referenceNumber());
        assertEquals("PRODUCTS", t.category());
        assertEquals("TELANGANA STATE MEDICAL SERVICES-HQ", t.organisation());
        assertEquals(LocalDateTime.of(2026, 11, 12, 19, 12), t.publishedAt());
        assertEquals(LocalDateTime.of(2026, 11, 27, 17, 0), t.closingAt());
        assertEquals(118170000.0, t.estimatedValue());
    }

    @Test
    void keepsApTsDocumentReferenceFromActionsCell() throws Exception {
        JsonNode row = mapper.readTree("[\"SCCL\",\"746793\",\"E0426O0174\",\"PRODUCTS\",\"Procurement of HSD\",\"1.00\","
                + "\"06/11/2026 10:23 AM\",\"20/11/2026 05:00 PM\",\"27/11/2026 05:00 PM\","
                + "\"<a href='#' onclick=\\\"viewBtn(679792,102,679782);\\\">v</a><a href='#' onclick=\\\"fnviewTenderDocuments(679782,679792);\\\">d</a>\"]");
        assertEquals("679782,679792", ApTsEprocCrawler.parseRow(row).docRef());
    }

    @Test
    void wordAndExcelFilesAreNotUnzipped() {
        byte[] zipMagic = {'P', 'K', 3, 4, 0, 0};
        GepnicSession.RawResponse docx = new GepnicSession.RawResponse(200, "application/octet-stream", null, zipMagic, "x");
        assertFalse(StateDocumentService.isZipArchive(docx, "NIT-520.docx"));
        assertFalse(StateDocumentService.isZipArchive(docx, "BOQ.xlsx"));
        assertTrue(StateDocumentService.isZipArchive(docx, "work_3887.zip"));
        assertTrue(StateDocumentService.isZipArchive(docx, null));
        GepnicSession.RawResponse pdf = new GepnicSession.RawResponse(200, "application/pdf", null, "%PDF".getBytes(), "x");
        assertFalse(StateDocumentService.isZipArchive(pdf, "a.zip"));
    }

    @Test
    void parsesAndhraNineColumnRowWithRupeeValue() throws Exception {
        JsonNode row = mapper.readTree("[\"APGENCO-S D S T P S\",\"1007538\",\"e-94/2026-27\",\"WORKS\","
                + "\"Replacement of Worn out Grinding rolls\",\"\\u20b9 5,58,229\",\"12/11/2026 07:00 PM\","
                + "\"27/11/2026 03:30 PM\",\"<a href='#'>view</a>\"]");
        PortalListCrawler.ListedTender t = ApTsEprocCrawler.parseRow(row);
        assertEquals("1007538", t.tenderId());
        assertEquals(558229.0, t.estimatedValue());
        assertEquals(LocalDateTime.of(2026, 11, 12, 19, 0), t.publishedAt());
        assertEquals(LocalDateTime.of(2026, 11, 27, 15, 30), t.closingAt(), "AP has no separate published column");
    }

    @Test
    void decodesAndhraBase64ListBody() throws Exception {
        String json = "{\"iTotalRecords\":-1,\"aaData\":[[\"X\",\"1\",\"R\",\"WORKS\",\"T\",\"\",\"12/11/2026 07:00 PM\",\"27/11/2026 03:30 PM\",\"\"]]}";
        String b64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        ApTsEprocCrawler c = new ApTsEprocCrawler();
        assertEquals(1, c.parseBody(b64).path("aaData").size());
        assertEquals(1, c.parseBody(json).path("aaData").size());
        assertThrows(java.io.IOException.class, () -> c.parseBody(""));
    }

    // ---- Gujarat nProcure ----

    @Test
    void parsesNprocureRowHtml() throws Exception {
        String html = "<html><body><span style=color:#f44336; >AMC-Ahmedabad Municipal Corporation<form action='/view-nit-home' method='POST'>"
                + "<input type='hidden' name='tenderid' id='tenderid_361717' value='361717'/><a href='#'>Tender Id :361717</a></form></span>"
                + "<span><form action='/view-nit-home' method='POST'><input type='hidden' name='tenderid' value='361717'/>"
                + "<a href='#'><strong>Name Of Work :</strong>PROCUREMENT OF FULLY MOTORIZED 4- SECTION ICU BED FOR AMC HOSPITAL</a></form></span>"
                + "<p> Estimated Contract Value : 4500000.00<p> Last Date & Time For Submission : 23-11-2026 17:30:00</body></html>";
        JsonNode row = mapper.createObjectNode().put("1", "AMC/HOSP/2026/77").put("2", html).put("3", "");
        PortalListCrawler.ListedTender t = NprocureCrawler.parseRow(row);
        assertEquals("361717", t.tenderId());
        assertEquals("AMC/HOSP/2026/77", t.referenceNumber());
        assertEquals("AMC-Ahmedabad Municipal Corporation", t.organisation());
        assertEquals("PROCUREMENT OF FULLY MOTORIZED 4- SECTION ICU BED FOR AMC HOSPITAL", t.title());
        assertEquals(4500000.0, t.estimatedValue());
        assertEquals(LocalDateTime.of(2026, 11, 23, 17, 30), t.closingAt());
        assertNull(t.publishedAt(), "nProcure does not list a published date");
    }

    @Test
    void nprocureEncryptionRoundTripsLikeTheSiteScript() throws Exception {
        byte[] salt = HexFormat.of().parseHex("0f1e2d3c4b5a69788796a5b4c3d2e1f0");
        byte[] iv = HexFormat.of().parseHex("00112233445566778899aabbccddeeff");
        String cipher = NprocureCrawler.encrypt("{\"a\":1}", "Ab3dE", salt, iv);
        assertEquals("{\"a\":1}", NprocureCrawler.decrypt(cipher, "Ab3dE", salt, iv));
    }

    @Test
    void nprocureRequestCarriesPagingInsideTheCiphertext() throws Exception {
        NprocureCrawler c = new NprocureCrawler();
        JsonNode wrapper = mapper.readTree(c.encryptedRequest(300, 150, 3, "", "0", "Tenders In Progress"));
        String passphrase = new String(Base64.getDecoder().decode(wrapper.get("key").asText()), StandardCharsets.UTF_8);
        assertEquals(5, passphrase.length());
        String plain = NprocureCrawler.decrypt(wrapper.get("jsonData").asText(), passphrase,
                HexFormat.of().parseHex(wrapper.get("salt").asText()), HexFormat.of().parseHex(wrapper.get("iv").asText()));
        JsonNode p = mapper.readTree(plain);
        assertEquals("Tenders In Progress", p.get("id").asText());
        Map<String, String> params = new HashMap<>();
        p.get("reqData").forEach(n -> params.put(n.get("name").asText(), n.get("value").asText()));
        assertEquals("300", params.get("iDisplayStart"));
        assertEquals("150", params.get("iDisplayLength"));
        assertEquals("3", params.get("sEcho"));
    }

    // ---- Bihar eProc 2.0 ----

    @Test
    void parsesBiharJsonWithDepartmentNames() throws Exception {
        JsonNode list = read("bihar-tenderlist-sample.json");
        Map<Long, String> depts = new HashMap<>();
        read("bihar-departments-sample.json").forEach(d -> depts.put(d.get("organizationId").asLong(), d.get("organizationName").asText()));
        PortalListCrawler.ListedTender t = BiharEproc2Crawler.parse(list.get(0), depts);
        assertEquals("141190", t.tenderId());
        assertEquals("RE-SBD NIT-23/MUZAFFARPUR/BCD/2026-27", t.referenceNumber());
        assertTrue(t.title().startsWith("CONSTRUCTION OF ONE NO. PANCHAYAT SARKAR BHAWAN"));
        assertEquals(depts.get(1026L), t.organisation());
        assertNotNull(t.publishedAt());
        assertTrue(t.closingAt().isAfter(t.publishedAt()));
        assertEquals("WORKS", t.category());
    }

    // ---- Manipur (NIC next-generation portal) ----

    @Test
    void parsesManipurLatestTenders() throws Exception {
        List<PortalListCrawler.ListedTender> rows = NgGepnicLatestCrawler.parse(read("manipur-latest-tenders.json"), "Manipur");
        assertEquals(10, rows.size());
        PortalListCrawler.ListedTender first = rows.get(0);
        assertEquals("7dJiRTJ6cyoicVY_g6Ynzw", first.tenderId());
        assertEquals("No. ITM/19/2025-DITC-DIT", first.referenceNumber());
        assertTrue(first.title().startsWith("Selection of Strategic Implementation Partner"));
        assertEquals("Government of Manipur", first.organisation());
        assertEquals(LocalDateTime.of(2026, 11, 18, 13, 0), first.closingAt());
        assertEquals(LocalDateTime.of(2026, 11, 18, 14, 0), first.openingAt());
        assertNull(first.publishedAt());
        assertEquals("7dJiRTJ6cyoicVY_g6Ynzw", first.docRef(), "the work item id is kept for document download");
    }

    @Test
    void mergesManipurDetailsAndListsDocumentUnits() throws Exception {
        JsonNode details = read("manipur-tender-details.json");
        PortalListCrawler.ListedTender base = NgGepnicLatestCrawler.parse(read("manipur-latest-tenders.json"), "Manipur").get(0);
        PortalListCrawler.ListedTender t = NgGepnicLatestCrawler.withDetails(base, details);
        assertEquals("2026_MoIT_3574_1", t.tenderId());
        assertEquals("7dJiRTJ6cyoicVY_g6Ynzw", t.docRef());
        assertTrue(t.organisation().startsWith("Ministry of Information Technology"));
        assertEquals(LocalDateTime.of(2026, 11, 12, 10, 10), t.publishedAt());
        assertEquals(LocalDateTime.of(2026, 11, 18, 13, 0), t.closingAt());
        assertEquals("SERVICES", t.category());
        assertEquals("10,00,000", t.extra().get("EMD Amount"));
        assertEquals("10,000", t.extra().get("Tender Fee"));

        List<NgGepnicLatestCrawler.DocUnit> units = NgGepnicLatestCrawler.documentUnits(details);
        assertEquals(2, units.size(), "one zip for the work item documents, one NIT document");
        assertTrue(units.get(0).zip());
        assertEquals("GZ3-66o5Fwetagql2qcfYA", units.get(0).docCode());
        assertFalse(units.get(1).zip());
        assertEquals("Tendernotice_1.pdf", units.get(1).fileName());
        assertEquals("Tendernotice_1.pdf", NgGepnicLatestCrawler.cleanFileName("Tendernotice_1.pdf (Signed)"));
    }

    // ---- Chhattisgarh (CHiPS) ----

    @Test
    void readsChipsPrintedCodeAndFormFields() throws Exception {
        org.jsoup.nodes.Document page = GepnicParserTest.fixture("chips-code-page.html", "https://eproc.cgstate.gov.in/CHEPS/business/getOpenRfqListAction.do");
        org.jsoup.nodes.Element form = page.selectFirst("form[name=rfqSearchViewForm]");
        assertNotNull(form);
        assertEquals("HSWW1x47m", ChipsCrawler.printedCode(form));
        List<String> names = ChipsCrawler.formFields(form).stream().map(Map.Entry::getKey).toList();
        assertTrue(names.contains("inputCaptchaValue"));
        assertTrue(names.contains("OWASP_CSRFTOKEN"), "the form's own CSRF token is sent: " + names);
        assertTrue(names.contains("rfqStatus"));
    }

    @Test
    void parsesChipsResultRows() throws Exception {
        org.jsoup.nodes.Document page = GepnicParserTest.fixture("chips-results-page1.html", "https://eproc.cgstate.gov.in/CHEPS/business/getOpenRfqListAction.do");
        List<PortalListCrawler.ListedTender> rows = ChipsCrawler.parseRows(page);
        assertEquals(100, rows.size());
        PortalListCrawler.ListedTender first = rows.get(0);
        assertEquals("203859", first.tenderId());
        assertEquals("203859,1", first.docRef());
        assertNotNull(first.publishedAt());
        assertNotNull(first.closingAt());
        assertTrue(first.closingAt().isAfter(first.publishedAt()));
        assertFalse(first.organisation().isBlank());
        assertTrue(rows.stream().anyMatch(r -> r.estimatedValue() != null), "values come from toFixSciNotation(...)");
        assertArrayEquals(new int[]{1, 16}, ChipsCrawler.pageMarker(page));
    }

    @Test
    void matchesHindiProductWords() {
        assertNotNull(StateTenderSyncService.relevanceReason(row("जिला अस्पताल हेतु चिकित्सा उपकरण क्रय", "Health Department")));
        assertNotNull(StateTenderSyncService.relevanceReason(row("छात्रावास हेतु पलंग एवं फर्नीचर प्रदाय", "Tribal Department")));
        assertNull(StateTenderSyncService.relevanceReason(row("वार्ड 50 में सड़क निर्माण कार्य", "UADD")));
    }

    // ---- TLS chain completion (Gujarat and Bihar omit the Sectigo intermediate) ----

    @Test
    void completesChainWithBundledIntermediate() throws Exception {
        X509Certificate leaf;
        try (InputStream in = getClass().getResourceAsStream("/fixtures/gepnic/nprocure-leaf.pem")) {
            leaf = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        List<X509Certificate> bundled = ChainCompletingTrustManager.loadBundled();
        assertEquals(1, bundled.size());
        ChainCompletingTrustManager tm = new ChainCompletingTrustManager(null, bundled);
        X509Certificate[] completed = tm.complete(new X509Certificate[]{leaf});
        assertNotNull(completed);
        assertEquals(2, completed.length);
        assertEquals("CN=Sectigo Public Server Authentication CA OV R36,O=Sectigo Limited,C=GB",
                completed[1].getSubjectX500Principal().getName());
        assertNull(tm.complete(new X509Certificate[]{bundled.get(0)}), "nothing to add for a chain that does not need it");
    }

    // ---- Relevance and saving ----

    @Test
    void relevanceSkipsLiftsTankersAndWaterWorks() {
        assertNull(StateTenderSyncService.relevanceReason(row("Replacement of 12 Nos. Passenger/Stretcher Lifts", "Civil Hospital")));
        assertNull(StateTenderSyncService.relevanceReason(row("Supply of Vehicle/Cesspool Tanker (Tractor-Based Tanker-Suction Machine)", "Municipal Board")));
        assertNull(StateTenderSyncService.relevanceReason(row("PROVIDING & SUPPLYING OF TRACTOR TRAILER MOUNTED SUCTION MACHINE", "Dhrol Nagarpalika")));
        assertNull(StateTenderSyncService.relevanceReason(row("Supply of SM Pump Set with Electrical Equipments", "Gujarat Water Supply and Sewerage Board")));
        assertNull(StateTenderSyncService.relevanceReason(row("Supply of laboratory instruments", "Public Health Engineering Department")));
        assertNotNull(StateTenderSyncService.relevanceReason(row("PROCUREMENT OF FULLY MOTORIZED 4- SECTION ICU BED", "AMC")));
        assertNotNull(StateTenderSyncService.relevanceReason(row("Procurement of Ophthalmology Equipment", "TGMSIDC Hyderabad")));
    }

    @Test
    void savesNonNicTendersWithPortalPrefixedIds() {
        TenderRepository repo = Mockito.mock(TenderRepository.class);
        Mockito.when(repo.findById(anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.findFirstBySourceId(anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.findFirstByRefNo(anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.save(any(Tender.class))).thenAnswer(i -> i.getArgument(0));
        StateTenderSyncService svc = new StateTenderSyncService();
        ReflectionTestUtils.setField(svc, "tenderRepository", repo);
        ReflectionTestUtils.setField(svc, "lookbackDays", 100_000);

        GepnicPortal ts = new GepnicPortal("TS", "Telangana", "Telangana", "https://tender.telangana.gov.in/login.html", true, null, "apts");
        PortalListCrawler fake = new PortalListCrawler() {
            public String type() { return "apts"; }
            public List<ListedTender> list(GepnicPortal p, GepnicSession s, LocalDateTime after) {
                return List.of(
                        new ListedTender("747415", "Procurement and Supply of Ophthalmology Equipment", "TGMSIDC/EQP/2026",
                                "TGMSIDC Hyderabad", LocalDateTime.of(2026, 11, 12, 19, 12), LocalDateTime.of(2026, 11, 27, 17, 0),
                                null, 118170000.0, "PRODUCTS"),
                        new ListedTender("746607", "Construction of temple complex with equipment rooms", "08/CE/2026",
                                "Health and Family Welfare", LocalDateTime.of(2026, 11, 5, 22, 29), null, null, null, "WORKS"));
            }
        };
        StateTenderSyncService.PortalResult r = svc.syncListPortal(ts, fake, new GepnicSession(0, java.time.Duration.ofSeconds(5)), false);

        assertNull(r.error);
        assertEquals(1, r.imported, "the WORKS row matched only on generic words and is skipped");
        ArgumentCaptor<Tender> saved = ArgumentCaptor.forClass(Tender.class);
        Mockito.verify(repo).save(saved.capture());
        Tender t = saved.getValue();
        assertEquals("st-ts-747415", t.getId());
        assertEquals("STATE-TS", t.getSource());
        assertEquals("TS-747415", t.getSourceId());
        assertEquals("TGMSIDC/EQP/2026", t.getRefNo());
        assertEquals("747415", t.getEprocurementPortalId());
        assertEquals("Non GeM", t.getTenderType());
        assertEquals("Telangana", t.getState());
        assertEquals(118170000.0, t.getEstimatedCost());
        assertEquals("2026-11-27 17:00:00", t.getDueDate());
        assertEquals("https://tender.telangana.gov.in/login.html", t.getOriginalUrl());
    }

    private static GepnicParser.TenderRow row(String title, String org) {
        return new GepnicParser.TenderRow("1", title, "R", org, null, null, null, null);
    }

    private JsonNode read(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/gepnic/" + name)) {
            return mapper.readTree(in);
        }
    }
}
