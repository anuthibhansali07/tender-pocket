package com.tenderpocket.services.state;

import com.tenderpocket.repositories.TenderRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;

/**
 * Dry run against the real portals; writes nothing. Skipped unless RUN_LIVE_STATE_SYNC=true.
 * Choose portals with STATE_LIVE_PORTALS (default GA,TR,MH), e.g.
 * {@code RUN_LIVE_STATE_SYNC=true STATE_LIVE_PORTALS=UP mvn test -Dtest=StateTenderSyncLiveTest}
 */
class StateTenderSyncLiveTest {

    @Test
    void dryRunAgainstLivePortals() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LIVE_STATE_SYNC")));
        String codes = System.getenv().getOrDefault("STATE_LIVE_PORTALS", "GA,TR,MH");

        TenderRepository repo = Mockito.mock(TenderRepository.class);
        Mockito.when(repo.findFirstByEprocurementPortalId(anyString())).thenReturn(Optional.empty());
        Mockito.when(repo.findFirstByRefNo(anyString())).thenReturn(Optional.empty());

        StateTenderSyncService service = new StateTenderSyncService();
        ReflectionTestUtils.setField(service, "tenderRepository", repo);
        ReflectionTestUtils.setField(service, "lookbackDays", 7);
        ReflectionTestUtils.setField(service, "requestPauseMs", 1000L);
        ReflectionTestUtils.setField(service, "requestTimeoutSeconds", 90);
        ReflectionTestUtils.setField(service, "parallelPortals", 3);
        ReflectionTestUtils.setField(service, "maxDetailsPerPortal", 60);
        ReflectionTestUtils.invokeMethod(service, "init");

        StateTenderSyncService.RunSummary summary = service.runNow(Arrays.asList(codes.split(",")), true);
        for (StateTenderSyncService.PortalResult p : summary.portals) {
            System.out.println("=== " + p.portal + " error=" + p.error);
            p.sample.forEach(s -> System.out.println("    " + s));
        }
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }

    /**
     * Downloads real documents from the portals that need no CAPTCHA (Telangana, Andhra Pradesh,
     * Gujarat, Bihar) into a temporary folder, and checks Manipur reaches its CAPTCHA. Writes nothing
     * to the database. Skipped unless RUN_LIVE_STATE_DOCS=true.
     */
    @Test
    void nonNicPortalDocuments() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LIVE_STATE_DOCS")));
        StateTenderSyncService sync = new StateTenderSyncService();
        ReflectionTestUtils.setField(sync, "parallelPortals", 1);
        ReflectionTestUtils.invokeMethod(sync, "init");
        java.util.Map<String, GepnicPortal> portals = new java.util.HashMap<>();
        sync.getPortals().forEach(p -> portals.put(p.code(), p));
        java.nio.file.Path tmp = java.nio.file.Files.createTempDirectory("state-docs");

        for (String code : System.getenv().getOrDefault("STATE_LIVE_DOC_PORTALS", "TS,AP,GJ,BR,MN").split(",")) {
            GepnicPortal portal = portals.get(code);
            GepnicSession session = new GepnicSession(800, java.time.Duration.ofSeconds(180));
            java.util.List<PortalListCrawler.ListedTender> listed = StateTenderSyncService.CRAWLERS.get(portal.kind())
                    .list(portal, session, java.time.LocalDateTime.now().minusDays(3));
            PortalListCrawler.ListedTender t = listed.stream()
                    .filter(x -> !"apts".equals(portal.kind()) || x.docRef() != null).findFirst().orElseThrow();

            com.tenderpocket.models.Tender tender = new com.tenderpocket.models.Tender();
            tender.setId((portal.idPrefix() + t.tenderId()).toLowerCase());
            tender.setSource(portal.sourceName());
            tender.setEprocurementPortalId(t.tenderId());
            tender.setPortalDocRef(t.docRef());
            TenderRepository repo = Mockito.mock(TenderRepository.class);
            Mockito.when(repo.findById(tender.getId())).thenReturn(Optional.of(tender));
            Mockito.when(repo.save(Mockito.any())).thenAnswer(i -> i.getArgument(0));

            StateDocumentService docs = new StateDocumentService();
            ReflectionTestUtils.setField(docs, "tenderRepository", repo);
            ReflectionTestUtils.setField(docs, "stateTenderSyncService", sync);
            ReflectionTestUtils.setField(docs, "uploadDir", tmp.toString());

            StateDocumentService.FetchResult r = docs.start(tender.getId());
            System.out.printf("=== %s tender %s: %s | %s | files=%s%n", code, t.tenderId(), r.status(), r.message(),
                    r.files().stream().map(f -> f.get("filename")).toList());
            if ("MN".equals(code)) {
                org.junit.jupiter.api.Assertions.assertEquals("CAPTCHA_REQUIRED", r.status(), r.message());
            } else {
                org.junit.jupiter.api.Assertions.assertEquals("DOWNLOADED", r.status(), code + ": " + r.message());
                org.junit.jupiter.api.Assertions.assertFalse(r.files().isEmpty());
            }
        }
    }

    /**
     * Opens a real tender's document download on the portal and checks that the portal's CAPTCHA
     * comes back for a person to read. It does not answer the CAPTCHA. Skipped unless
     * RUN_LIVE_STATE_DOCS=true; pick the tender with STATE_LIVE_TENDER (default 2026_DHS_1352679_1 on MH).
     */
    @Test
    void documentDownloadReachesPortalCaptcha() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("RUN_LIVE_STATE_DOCS")));
        String tenderId = System.getenv().getOrDefault("STATE_LIVE_TENDER", "2026_DHS_1352679_1");
        String authority = System.getenv().getOrDefault("STATE_LIVE_AUTHORITY", "Directorate of Health Services, Mumbai | Dy. Director of Health Services Akola");

        com.tenderpocket.models.Tender t = new com.tenderpocket.models.Tender();
        t.setId("gep-mh-" + tenderId.toLowerCase());
        t.setSource("GePNIC-MH");
        t.setEprocurementPortalId(tenderId);
        t.setAuthority(authority);

        TenderRepository repo = Mockito.mock(TenderRepository.class);
        Mockito.when(repo.findById(t.getId())).thenReturn(Optional.of(t));

        StateTenderSyncService sync = new StateTenderSyncService();
        ReflectionTestUtils.setField(sync, "parallelPortals", 1);
        ReflectionTestUtils.invokeMethod(sync, "init");

        StateDocumentService docs = new StateDocumentService();
        ReflectionTestUtils.setField(docs, "tenderRepository", repo);
        ReflectionTestUtils.setField(docs, "stateTenderSyncService", sync);

        StateDocumentService.FetchResult r = docs.start(t.getId());
        System.out.println("status=" + r.status() + " message=" + r.message()
                + " image=" + (r.captchaImage() == null ? null : r.captchaImage().substring(0, Math.min(40, r.captchaImage().length())) + "..."));
        org.junit.jupiter.api.Assertions.assertEquals("CAPTCHA_REQUIRED", r.status(), r.message());
        org.junit.jupiter.api.Assertions.assertTrue(r.captchaImage().startsWith("data:image/"));
        org.junit.jupiter.api.Assertions.assertNotNull(r.token());
    }
}
