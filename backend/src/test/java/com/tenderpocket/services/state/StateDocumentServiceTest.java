package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

class StateDocumentServiceTest {

    @TempDir
    Path tmp;

    private StateDocumentService service;
    private TenderRepository repo;

    @BeforeEach
    void setUp() {
        service = new StateDocumentService();
        repo = Mockito.mock(TenderRepository.class);
        Mockito.when(repo.save(any(Tender.class))).thenAnswer(inv -> inv.getArgument(0));
        ReflectionTestUtils.setField(service, "tenderRepository", repo);
        ReflectionTestUtils.setField(service, "uploadDir", tmp.toString());
        ReflectionTestUtils.setField(service, "awsS3Enabled", false);
    }

    @Test
    void findsZipLinkOnDetailPage() throws Exception {
        String url = StateDocumentService.findZipLink(
                GepnicParserTest.fixture("mahatenders-detail-2026_DHS_1352679_1.html", GepnicParserTest.BASE));
        assertNotNull(url);
        assertTrue(url.startsWith("https://mahatenders.gov.in/nicgep/app?component=%24DirectLink"), url);
        assertTrue(url.contains("page=FrontEndTenderDetails"));
    }

    @Test
    void parsesPortalCaptchaForm() throws Exception {
        StateDocumentService.CaptchaForm form = StateDocumentService.parseCaptchaForm(
                GepnicParserTest.fixture("mahatenders-doc-captcha.html", GepnicParserTest.BASE));
        assertNotNull(form);
        assertEquals("https://mahatenders.gov.in/nicgep/app", form.action());
        assertEquals("captchaText", form.answerField());
        assertTrue(form.imageSrc().startsWith("data:image/png;base64,"));
        Map<String, String> fields = form.fields().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));
        assertEquals("frmCaptcha", fields.get("component"));
        assertEquals("DocDownCaptcha", fields.get("page"));
        assertTrue(fields.containsKey("tokenSecret"));
        assertTrue(fields.containsKey("formids"));
        assertEquals("Submit", fields.get("Submit"));
        // Only the CAPTCHA form's fields, not the second form on the page.
        assertFalse(fields.containsKey("domainUrl"));
    }

    @Test
    void noCaptchaFormOnOrdinaryPage() throws Exception {
        assertNull(StateDocumentService.parseCaptchaForm(
                GepnicParserTest.fixture("mahatenders-detail-2026_DHS_1352679_1.html", GepnicParserTest.BASE)));
    }

    @Test
    void storesZipLikeGemDocuments() throws Exception {
        Tender t = new Tender();
        t.setId("gep-mh-2026_dhs_1352679_1");
        t.setEprocurementPortalId("2026_DHS_1352679_1");
        t.setDownloadedDocs("[{\"name\":\"Technical Specification Sheet (PDF)\",\"filename\":\"Technical_Specification_Sheet_x.pdf\",\"local_path\":\"/documents/x/Technical_Specification_Sheet_x.pdf\",\"created_date\":\"2026-11-10\"}]");

        byte[] zip = zipOf(Map.of(
                "2026_DHS_1352679_1/Tendernotice_1.pdf", "%PDF-notice",
                "2026_DHS_1352679_1/Tenderdocument.pdf", "%PDF-document",
                "2026_DHS_1352679_1/BOQ_2309044.xls", "xls-bytes"));
        GepnicSession.RawResponse resp = new GepnicSession.RawResponse(200, "application/octet-stream",
                "attachment; filename=\"2026_DHS_1352679_1.zip\"", zip, "https://mahatenders.gov.in/nicgep/app");

        StateDocumentService.FetchResult result = service.store(t, resp);

        assertEquals("DOWNLOADED", result.status());
        Path dir = tmp.resolve("gep-mh-2026_dhs_1352679_1");
        assertTrue(Files.exists(dir.resolve("Tendernotice_1.pdf")));
        assertTrue(Files.exists(dir.resolve("Tenderdocument.pdf")));
        assertTrue(Files.exists(dir.resolve("BOQ_2309044.xls")));
        assertTrue(Files.exists(dir.resolve("Portal_Tender_Documents_2026_DHS_1352679_1.zip")));
        assertEquals("%PDF-document", Files.readString(dir.resolve("Tenderdocument.pdf"), StandardCharsets.UTF_8));

        assertEquals("/documents/gep-mh-2026_dhs_1352679_1/Tenderdocument.pdf", t.getDocumentUrl());
        JsonNode docs = new ObjectMapper().readTree(t.getDownloadedDocs());
        assertEquals(5, docs.size(), "existing spec sheet kept, four portal files added");
        assertEquals("Technical_Specification_Sheet_x.pdf", docs.get(0).get("filename").asText());
        List<String> names = new java.util.ArrayList<>();
        docs.forEach(d -> names.add(d.get("filename").asText()));
        assertTrue(names.contains("Tendernotice_1.pdf"));
        for (JsonNode d : docs) {
            assertTrue(d.has("local_path") && d.has("name") && d.has("created_date"));
        }
        Mockito.verify(repo).save(t);
    }

    @Test
    void storingTwiceDoesNotDuplicateEntries() throws Exception {
        Tender t = new Tender();
        t.setId("gep-tr-1");
        byte[] zip = zipOf(Map.of("Tendernotice_1.pdf", "%PDF"));
        GepnicSession.RawResponse resp = new GepnicSession.RawResponse(200, "application/zip", null, zip, "https://x");
        service.store(t, resp);
        service.store(t, resp);
        assertEquals(2, new ObjectMapper().readTree(t.getDownloadedDocs()).size());
    }

    @Test
    void zipEntriesCannotEscapeTheTenderFolder() throws Exception {
        Tender t = new Tender();
        t.setId("gep-ga-1");
        byte[] zip = zipOf(Map.of("../../evil.pdf", "%PDF-1", "..\\win.pdf", "%PDF-2"));
        service.store(t, new GepnicSession.RawResponse(200, "application/zip", null, zip, "https://x"));
        assertFalse(Files.exists(tmp.resolve("evil.pdf")));
        assertTrue(Files.exists(tmp.resolve("gep-ga-1").resolve("evil.pdf")));
        assertTrue(Files.exists(tmp.resolve("gep-ga-1").resolve("win.pdf")));
    }

    @Test
    void findsNitZipAndWorkItemLinksButNotPopupsOrNavigation() throws Exception {
        List<String> links = StateDocumentService.findDocumentLinks(
                GepnicParserTest.fixture("mahatenders-detail-2026_DHS_1352679_1.html", GepnicParserTest.BASE));
        assertTrue(links.stream().anyMatch(l -> l.contains("component=docDownoad")), "NIT document link");
        assertTrue(links.stream().anyMatch(l -> l.contains("%24DirectLink_8")), "zip link");
        // Work item file names are plain text; their DirectLink_9 links only open a description popup,
        // so work item files arrive through the zip.
        assertTrue(links.stream().noneMatch(l -> l.contains("%24DirectLink_9") || l.contains("%24DirectLink_1&")
                || l.contains("%24DirectLink_11") || l.contains("%24DirectLink&")), "no popup, back or 'view more' links: " + links);
        assertEquals(2, links.size(), links.toString());
    }

    @Test
    void storesSeveralDownloadsAndSkipsDuplicates() throws Exception {
        Tender t = new Tender();
        t.setId("gep-central-x");
        t.setEprocurementPortalId("2026_X_1_1");
        byte[] boqZip = zipOf(Map.of("BOQ_1.xls", "boq"));
        GepnicSession.RawResponse zip = new GepnicSession.RawResponse(200, "application/zip", "attachment; filename=work_1.zip", boqZip, "https://x");
        GepnicSession.RawResponse nit = new GepnicSession.RawResponse(200, "application/pdf", "attachment; filename=\"Tendernotice_1.pdf\"",
                "%PDF-nit".getBytes(StandardCharsets.UTF_8), "https://x");
        GepnicSession.RawResponse boqAgain = new GepnicSession.RawResponse(200, "application/vnd.ms-excel", "attachment; filename=BOQ_1.xls",
                "boq".getBytes(StandardCharsets.UTF_8), "https://x");

        StateDocumentService.FetchResult r = service.store(t, List.of(zip, nit, boqAgain));

        List<String> names = r.files().stream().map(f -> f.get("filename")).toList();
        assertEquals(List.of("Portal_Tender_Documents_2026_X_1_1.zip", "BOQ_1.xls", "Tendernotice_1.pdf"), names);
        assertEquals("/documents/gep-central-x/Tendernotice_1.pdf", t.getDocumentUrl(), "the notice PDF becomes the main document");
    }

    @Test
    void topOrganisationFromStoredAuthority() {
        assertEquals("Directorate of Health Services, Mumbai",
                StateDocumentService.topOrganisation("Directorate of Health Services, Mumbai | Dy. Director of Health Services Akola"));
        assertEquals("PWD", StateDocumentService.topOrganisation("PWD||Division 1"));
        assertNull(StateDocumentService.topOrganisation(" "));
    }

    private static byte[] zipOf(Map<String, String> files) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> f : new java.util.TreeMap<>(files).entrySet()) {
                z.putNextEntry(new ZipEntry(f.getKey()));
                z.write(f.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
