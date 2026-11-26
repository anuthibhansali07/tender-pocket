package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenderpocket.repositories.TenderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** An alert email prints 5 tenders and links to its full list; the full list is what must be imported. */
class EmailFullListTest {

    private static final String TRACKING_LINK = "https://r.tenders.bidsnrfp.com/tr/cl/sanitized-tracking-token";
    private static final String TENDER247_ADDRESS = "https://www.tender247.com/mailtenders?key=abc%2Bdef%3D%3D&utm=1";

    private final ObjectMapper json = new ObjectMapper();
    private RestTemplate restTemplate;
    private TenderRepository repository;
    private EmailSyncService service;
    private String alertEmail;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = mock(RestTemplate.class);
        repository = mock(TenderRepository.class);
        service = new EmailSyncService();
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        ReflectionTestUtils.setField(service, "tenderRepository", repository);
        // "Click here to view all (179) Fresh Tenders", with 5 tenders printed in the email
        alertEmail = Files.readString(Path.of("src/test/resources/fixtures/email-179-medical-equipment.html"), StandardCharsets.UTF_8);

        ObjectNode login = json.createObjectNode();
        login.put("token", "jwt").put("user_id", "1003059").put("user_query_id", "q-7").put("mail_date", "2026-11-18");
        ObjectNode loginRoot = json.createObjectNode();
        loginRoot.set("Data", json.createArrayNode().add(login));
        when(restTemplate.postForEntity(contains("/login/user/key"), any(), eq(JsonNode.class)))
                .thenReturn(new ResponseEntity<>(loginRoot, HttpStatus.OK));
    }

    private ResponseEntity<JsonNode> page(int from, int count) {
        ArrayNode data = json.createArrayNode();
        for (int i = 0; i < count; i++) {
            ObjectNode doc = data.addObject();
            doc.put("tender_id", 106000000 + from + i);
            doc.put("security_code", "sec");
            doc.put("requirement_workbrief", "supply of item " + (from + i));
            doc.put("organization_name", "Health Department");
            doc.put("site_location", "Pune");
            doc.put("submission_enddate", "05-12-2026");
            doc.put("tender_number", "2026_DHFW_" + (from + i) + "_1");
            doc.put("nameof_website", "https://mahatenders.gov.in");
        }
        ObjectNode root = json.createObjectNode();
        root.set("Data", data);
        return new ResponseEntity<>(root, HttpStatus.OK);
    }

    @Test
    void followsTheViewAllLinkAndReadsEveryPageOfTheList() {
        service.redirectFollower = href -> {
            assertEquals(TRACKING_LINK, href);
            return TENDER247_ADDRESS;
        };
        when(restTemplate.postForEntity(contains("/search-tender"), any(), eq(JsonNode.class)))
                .thenReturn(page(0, 100), page(100, 79));

        EmailSyncService.EmailTenders email = service.readAlertEmail(alertEmail, new HashSet<>());

        assertTrue(email.fromFullList);
        assertFalse(email.fullListFailed);
        assertEquals(179, email.tenders.size(), "all 179 listed tenders, not the 5 printed in the email");
        assertEquals("2026_DHFW_178_1", email.tenders.get(178).get("ref_no"));

        // The key comes from the address the tracking link redirects to, and the list is requested page by page.
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<HttpEntity<Map<String, Object>>> requests = ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate, times(2)).postForEntity(contains("/search-tender"), requests.capture(), eq(JsonNode.class));
        assertEquals(1, requests.getAllValues().get(0).getBody().get("page_no"));
        assertEquals(2, requests.getAllValues().get(1).getBody().get("page_no"));
        assertEquals("q-7", requests.getAllValues().get(0).getBody().get("user_email_service_query_id"));
        assertTrue(requests.getAllValues().get(0).getBody().containsKey("sort_by"), "Tender247 answers a short request with no tenders");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<HttpEntity<Map<String, String>>> login = ArgumentCaptor.forClass((Class) HttpEntity.class);
        verify(restTemplate).postForEntity(contains("/login/user/key"), login.capture(), eq(JsonNode.class));
        assertEquals("abc+def==", login.getValue().getBody().get("key"));
    }

    @Test
    void theSameDailyListIsReadOnlyOnce() {
        service.redirectFollower = href -> TENDER247_ADDRESS;
        when(restTemplate.postForEntity(contains("/search-tender"), any(), eq(JsonNode.class))).thenReturn(page(0, 12));
        Set<String> listsRead = new HashSet<>();

        assertEquals(12, service.readAlertEmail(alertEmail, listsRead).tenders.size());
        EmailSyncService.EmailTenders noonEmail = service.readAlertEmail(alertEmail, listsRead);

        assertTrue(noonEmail.sameListAsEarlierEmail);
        assertTrue(noonEmail.tenders.isEmpty());
        verify(restTemplate, times(1)).postForEntity(contains("/search-tender"), any(), eq(JsonNode.class));
    }

    @Test
    void whenTheListCannotBeReadThePrintedRowsAreUsedAndTheEmailIsRetried() {
        service.redirectFollower = href -> { throw new IllegalStateException("network down"); };

        EmailSyncService.EmailTenders email = service.readAlertEmail(alertEmail, new HashSet<>());

        assertFalse(email.fromFullList);
        assertTrue(email.fullListFailed, "the email must stay unread so the next sync retries the full list");
        assertEquals(5, email.tenders.size());
    }

    @Test
    void anEmptyListFromTender247CountsAsAFailedRead() {
        service.redirectFollower = href -> TENDER247_ADDRESS;
        when(restTemplate.postForEntity(contains("/search-tender"), any(), eq(JsonNode.class))).thenReturn(page(0, 0));

        EmailSyncService.EmailTenders email = service.readAlertEmail(alertEmail, new HashSet<>());

        assertTrue(email.fullListFailed);
        assertEquals(5, email.tenders.size());
    }

    @Test
    void aLinkThatLeadsToNoListFallsBackWithoutRetrying() {
        service.redirectFollower = href -> "https://www.tender247.com/";

        EmailSyncService.EmailTenders email = service.readAlertEmail(alertEmail, new HashSet<>());

        assertFalse(email.fullListFailed);
        assertEquals(5, email.tenders.size());
    }

    @Test
    void catchUpCanSkipTendersPastTheirDueDate() {
        when(repository.findFirstByRefNo(anyString())).thenReturn(java.util.Optional.empty());
        when(repository.findById(anyString())).thenReturn(java.util.Optional.empty());
        when(repository.findFirstBySourceId(anyString())).thenReturn(java.util.Optional.empty());
        java.util.List<Map<String, String>> tenders = java.util.List.of(
                Map.of("id", "106000001", "url", "https://www.tender247.com/x", "ref_no", "A/1", "due_date", "01-01-2020"),
                Map.of("id", "106000002", "url", "https://www.tender247.com/y", "ref_no", "A/2", "due_date", "2099-01-01T10:00:00"));

        int[] counts = service.importTenders(tenders, true);

        assertArrayEquals(new int[]{1, 0, 1}, counts);
        assertArrayEquals(new int[]{2, 0, 0}, service.importTenders(tenders, false));
    }

    @Test
    void dueDatesInBothFormatsAreUnderstood() {
        LocalDate today = LocalDate.of(2026, 11, 26);
        assertTrue(EmailSyncService.isPastDue("25-11-2026", today));
        assertTrue(EmailSyncService.isPastDue("2026-11-25T17:00:00", today));
        assertFalse(EmailSyncService.isPastDue("26-11-2026", today));
        assertFalse(EmailSyncService.isPastDue("05/12/2026 3:00 PM", today));
        assertFalse(EmailSyncService.isPastDue("", today));
        assertFalse(EmailSyncService.isPastDue("Refer document", today));
        assertFalse(EmailSyncService.isPastDue(null, today));
    }
}
