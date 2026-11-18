package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderpocket.models.Tender;
import com.tenderpocket.models.ProcessedAlertEmail;
import com.tenderpocket.repositories.ProcessedAlertEmailRepository;
import com.tenderpocket.repositories.TenderRepository;
import com.tenderpocket.utils.TenderClassifier;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.search.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EmailSyncService {

    @Autowired
    private TenderRepository tenderRepository;

    @Value("${imap.host}")
    private String imapHost;

    @Value("${imap.port}")
    private int imapPort;

    @Value("${imap.username}")
    private String imapUsername;

    @Value("${imap.password}")
    private String imapPassword;

    @Value("${imap.sender-filter}")
    private String senderFilter;

    private final RestTemplate restTemplate = new RestTemplate(new Tender247RequestFactory());

    /** Imported alert emails, so each is imported once without touching its read/unread state. */
    @Autowired(required = false)
    private ProcessedAlertEmailRepository processedAlertEmailRepository;

    /** Used only when the database table is unavailable. */
    private final Set<String> processedInMemory = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Tender247 search lists already fetched in the current run (the noon and evening alerts of one search share a list). */
    private final Set<String> listsFetchedThisRun = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static final int PROCESSED_EMAIL_KEEP_DAYS = 7;
    static final int MAX_LIST_PAGES = 20;
    private static final int LIST_PAGE_SIZE = 100;

    /**
     * Tender247's API host is {@code t247_api.tender247.com}. Java refuses host names with an
     * underscore during the certificate name check, so every call failed. For that host only, this
     * checks the name against the certificate's {@code *.tender247.com} entry itself. The
     * certificate chain is still verified as usual; nothing else is relaxed.
     */
    static final class Tender247RequestFactory extends SimpleClientHttpRequestFactory {
        Tender247RequestFactory() {
            setConnectTimeout(15_000);
            setReadTimeout(30_000);
        }

        @Override
        protected void prepareConnection(java.net.HttpURLConnection connection, String httpMethod) throws IOException {
            super.prepareConnection(connection, httpMethod);
            if (connection instanceof javax.net.ssl.HttpsURLConnection https
                    && connection.getURL().getHost().toLowerCase(Locale.ROOT).endsWith(".tender247.com")
                    && connection.getURL().getHost().contains("_")) {
                https.setHostnameVerifier((host, session) -> {
                    try {
                        return certificateCovers(host, (java.security.cert.X509Certificate) session.getPeerCertificates()[0]);
                    } catch (Exception e) {
                        return false;
                    }
                });
            }
        }
    }

    /** True when the certificate names {@code host} exactly or through a single-label wildcard. */
    static boolean certificateCovers(String host, java.security.cert.X509Certificate cert) throws Exception {
        if (host == null || cert == null || cert.getSubjectAlternativeNames() == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (List<?> san : cert.getSubjectAlternativeNames()) {
            if (san.size() < 2 || !Integer.valueOf(2).equals(san.get(0))) continue; // 2 = DNS name
            String name = String.valueOf(san.get(1)).toLowerCase(Locale.ROOT);
            if (name.equals(h)) return true;
            if (name.startsWith("*.")) {
                String suffix = name.substring(1); // ".tender247.com"
                if (h.endsWith(suffix) && h.indexOf('.') == h.length() - suffix.length()) return true;
            }
        }
        return false;
    }
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired(required = false)
    private FetchStatusRecorder fetchStatusRecorder;

    /** One Tender247 import at a time, whichever trigger (schedule, retry, Fetch Status page, API) started it. */
    private final AtomicBoolean syncRunning = new AtomicBoolean(false);

    /** Thrown when an import is requested while another one is still reading the inbox. */
    public static class SyncAlreadyRunningException extends RuntimeException {
        public SyncAlreadyRunningException() {
            super("A Tender247 email import is already running");
        }
    }

    /** Outcome of one Tender247 import. */
    public static final class SyncResult {
        /** False when no inbox is set up (IMAP_USERNAME / IMAP_PASSWORD empty); nothing was read. */
        public final boolean configured;
        public final int emailsRead;
        public final int imported;
        /** "subject (date): reason" for each alert email that could not be read; those emails stay unread. */
        public final List<String> failedEmails;

        SyncResult(boolean configured, int emailsRead, int imported, List<String> failedEmails) {
            this.configured = configured;
            this.emailsRead = emailsRead;
            this.imported = imported;
            this.failedEmails = List.copyOf(failedEmails);
        }

        public boolean hasFailures() {
            return !failedEmails.isEmpty();
        }
    }

    public boolean isConfigured() {
        return imapUsername != null && !imapUsername.isEmpty() && imapPassword != null && !imapPassword.isEmpty();
    }

    public boolean isSyncRunning() {
        return syncRunning.get();
    }

    public int syncEmails() throws Exception {
        return syncEmailsDetailed().imported;
    }

    /** Imports the Tender247 alert emails and records the outcome for the Fetch Status page. */
    public SyncResult syncEmailsDetailed() throws Exception {
        if (!syncRunning.compareAndSet(false, true)) throw new SyncAlreadyRunningException();
        List<String> source = List.of(FetchStatusRecorder.TENDER247);
        if (fetchStatusRecorder != null) fetchStatusRecorder.markStarted(source);
        try {
            SyncResult result = runSync();
            if (fetchStatusRecorder != null) {
                fetchStatusRecorder.recordTender247(result.configured, result.imported, result.failedEmails.size(), false);
            }
            return result;
        } catch (Exception e) {
            if (fetchStatusRecorder != null) fetchStatusRecorder.recordTender247(true, 0, 0, true);
            throw e;
        } finally {
            if (fetchStatusRecorder != null) fetchStatusRecorder.markFinished(source);
            syncRunning.set(false);
        }
    }

    private SyncResult runSync() throws Exception {
        int tendersImported = 0;
        int emailsRead = 0;
        List<String> failedEmails = new ArrayList<>();
        if (!isConfigured()) {
            System.out.println("[EmailSync] IMAP credentials not configured. Skipping email sync.");
            return new SyncResult(false, 0, 0, failedEmails);
        }

        System.out.println(String.format("Connecting to IMAP mail server at %s:%d...", imapHost, imapPort));

        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", imapHost);
        properties.put("mail.imaps.port", String.valueOf(imapPort));
        properties.put("mail.imaps.ssl.enable", "true");
        properties.put("mail.imaps.connectiontimeout", "5000");
        properties.put("mail.imaps.timeout", "5000");
        // Never mark emails as read: the alert inbox is shared with people who read them.
        properties.put("mail.imaps.peek", "true");

        Session emailSession = Session.getInstance(properties);
        Store store = emailSession.getStore("imaps");
        
        int retries = 2;
        for (int i = 0; i < retries; i++) {
            try {
                store.connect(imapHost, imapUsername, imapPassword);
                break;
            } catch (Exception e) {
                if (i == retries - 1) throw e;
                System.out.println(String.format("[Network Retry] IMAP connection failed: %s. Retrying in 3s...", e.getMessage()));
                Thread.sleep(3000);
            }
        }

        try {
            Folder inbox = store.getFolder("INBOX");
            // Read-only: the import never changes an email's read/unread state or anything else.
            inbox.open(Folder.READ_ONLY);
            listsFetchedThisRun.clear();
            forgetOldProcessedEmails();

            LocalDate todayIST = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            LocalDate cutoffDate = todayIST.minusDays(1); // Last 2 days: today and yesterday
            Date cutoffDateObj = Date.from(cutoffDate.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant());

            // Read or unread does not matter: the team opens these alerts. Emails already imported
            // are skipped by their Message-ID instead.
            SearchTerm dateTerm = new ReceivedDateTerm(ComparisonTerm.GE, cutoffDateObj);
            SearchTerm searchTerm = (senderFilter != null && !senderFilter.isEmpty())
                    ? new AndTerm(new FromStringTerm(senderFilter), dateTerm) : dateTerm;

            Message[] messages;
            try {
                messages = inbox.search(searchTerm);
            } catch (Exception e) {
                // Look only at the newest emails rather than the whole mailbox; the date and sender
                // checks below still apply.
                int total = inbox.getMessageCount();
                int from = Math.max(1, total - 299);
                System.out.println("[EmailSync] IMAP search failed (" + e.getMessage() + "). Checking the newest "
                        + (total - from + 1) + " email(s) instead.");
                messages = total == 0 ? new Message[0] : inbox.getMessages(from, total);
            }

            System.out.println(String.format("Found %d alert email(s) since %s IST (read or unread).", messages.length, cutoffDate));

            for (Message msg : messages) {
                String subject = msg.getSubject();
                String from = (msg.getFrom() != null && msg.getFrom().length > 0) ? msg.getFrom()[0].toString() : "Unknown";

                // Enforce 2-day cutoff (Asia/Kolkata) on message received / sent date
                Date msgDate = msg.getReceivedDate() != null ? msg.getReceivedDate() : msg.getSentDate();
                if (msgDate != null) {
                    LocalDate emailLocalDate = msgDate.toInstant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
                    if (emailLocalDate.isBefore(cutoffDate)) {
                        continue; // older than the 2-day window
                    }
                }

                if (senderFilter != null && !senderFilter.isEmpty() && !from.toLowerCase().contains(senderFilter.toLowerCase())) {
                    continue;
                }

                String emailKey = alertEmailKey(msg, from, subject, msgDate);
                if (alreadyProcessed(emailKey)) continue;
                System.out.println(String.format("Processing email: %s from %s", subject, from));

                try {
                String content = getTextFromMessage(msg);
                ParsedEmail parsed = parseAlertEmail(content);
                List<Map<String, String>> tenders = parsed.tenders;
                System.out.println(String.format("Extracted %d tenders from email.", tenders.size()));

                for (Map<String, String> tData : tenders) {
                    String id = tData.get("id");
                    String link = tData.get("url");
                    String refNo = tData.getOrDefault("ref_no", "").trim();
                    String title = tData.getOrDefault("title", "Tender " + id);
                    String nameofWebsite = tData.getOrDefault("nameof_website", "");

                    if (id == null || link == null) continue;

                    // If refNo is still blank, attempt to extract a GeM bid number from the title or link
                    if (refNo.isEmpty()) {
                        String extracted = TenderClassifier.extractGemBidNumber(title, link);
                        if (extracted != null) {
                            refNo = extracted;
                        }
                    }

                    // Cross-source deduplication check:
                    // 1) Match by official refNo (e.g. GeM bid number) if available and not a placeholder
                    // 2) Match by primary key id (Tender247 ID) ONLY if existing record belongs to Tender247 namespace or refNo matches
                    // 3) Match by sourceId ONLY if existing record belongs to Tender247 namespace or refNo matches
                    Optional<Tender> existingOpt = Optional.empty();
                    if (!TenderClassifier.isPlaceholderRefNo(refNo)) {
                        existingOpt = tenderRepository.findFirstByRefNo(refNo);
                    }
                    if (existingOpt.isEmpty() && id != null && !id.isBlank()) {
                        Optional<Tender> byId = tenderRepository.findById(id);
                        if (byId.isPresent() && isLegitimateT247Match(byId.get(), id, refNo)) {
                            existingOpt = byId;
                        }
                    }
                    if (existingOpt.isEmpty() && id != null && !id.isBlank()) {
                        Optional<Tender> bySourceId = tenderRepository.findFirstBySourceId(id);
                        if (bySourceId.isPresent() && isLegitimateT247Match(bySourceId.get(), id, refNo)) {
                            existingOpt = bySourceId;
                        }
                    }

                    if (existingOpt.isPresent()) {
                        Tender existing = existingOpt.get();
                        enrichExistingRecordFromTender247(existing, tData, refNo, link);
                        tenderRepository.save(existing);
                        System.out.println(String.format("[EmailSync] Enriched existing tender id=%s refNo=%s with classification=%s",
                                existing.getId(), existing.getRefNo(), existing.getTenderType()));
                        continue;
                    }

                    // Prevent primary key collision with unrelated tender from another namespace
                    String tenderId = id;
                    if (tenderRepository.existsById(id)) {
                        tenderId = "t247-" + id;
                    }

                    Tender tender = new Tender();
                    tender.setId(tenderId);
                    tender.setSourceId(id);
                    tender.setRefNo(refNo);
                    tender.setTitle(title);
                    tender.setAuthority(tData.getOrDefault("authority", ""));
                    tender.setEstimatedCost(null);
                    tender.setEstimatedCostRaw(tData.getOrDefault("estimated_cost_raw", "N/A"));
                    tender.setEmd(null);
                    tender.setEmdRaw(tData.getOrDefault("emd_raw", "N/A"));
                    tender.setDocumentFee(null);
                    tender.setDocumentFeeRaw(tData.getOrDefault("document_fee_raw", "N/A"));
                    tender.setLocation(tData.getOrDefault("location", "N/A"));
                    tender.setSector(nameofWebsite.isEmpty() ? null : nameofWebsite);
                    tender.setDueDate(tData.getOrDefault("due_date", ""));
                    tender.setOpeningDate(null);
                    tender.setDocumentUrl(null);
                    tender.setOriginalUrl(link);
                    tender.setStatus("Issued");
                    tender.setScrapedAt(new Date().toString());
                    tender.setEntryDate(LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM yyyy")));
                    tender.setSource("Tender247");

                    tenderRepository.save(tender);
                    tendersImported++;
                }

                if (parsed.fullListError != null) {
                    // Only the tenders printed in the email were imported. It is not marked as
                    // imported, so the next run (or the 2-hour retry) fetches the full list.
                    throw new IllegalStateException("could not open the full tender list from Tender247 ("
                            + parsed.fullListError + "); imported the " + tenders.size() + " tender(s) shown in the email");
                }

                markProcessed(emailKey, subject, tenders.size());
                emailsRead++;
                } catch (Exception e) {
                    String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    String when = msgDate != null
                            ? msgDate.toInstant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate().toString()
                            : "unknown date";
                    failedEmails.add("\"" + subject + "\" (" + when + "): " + reason);
                    System.err.println("[EmailSync] Could not read alert email \"" + subject + "\": " + reason
                            + ". The next run tries it again.");
                }
            }

            inbox.close(false);
        } finally {
            store.close();
            System.out.println("Disconnected from IMAP store.");
        }

        return new SyncResult(true, emailsRead, tendersImported, failedEmails);
    }

    /** Stable key for an alert email: its Message-ID, or sender + subject + date when it has none. */
    static String alertEmailKey(Message msg, String from, String subject, Date date) {
        String id = null;
        try {
            String[] h = msg.getHeader("Message-ID");
            if (h != null && h.length > 0 && h[0] != null && !h[0].isBlank()) id = h[0].trim();
        } catch (Exception ignored) {
        }
        String basis = id != null ? id : from + "|" + subject + "|" + (date == null ? "" : date.getTime());
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(basis.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(basis.hashCode());
        }
    }

    private boolean alreadyProcessed(String key) {
        if (processedInMemory.contains(key)) return true;
        if (processedAlertEmailRepository == null) return false;
        try {
            return processedAlertEmailRepository.existsById(key);
        } catch (Exception e) {
            return false;
        }
    }

    private void markProcessed(String key, String subject, int tenders) {
        processedInMemory.add(key);
        if (processedAlertEmailRepository == null) return;
        try {
            processedAlertEmailRepository.save(new ProcessedAlertEmail(key,
                    subject == null ? "" : subject.substring(0, Math.min(500, subject.length())), tenders,
                    java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))));
        } catch (Exception e) {
            System.err.println("[EmailSync] Could not remember imported email: " + e.getMessage());
        }
    }

    private void forgetOldProcessedEmails() {
        if (processedAlertEmailRepository == null) return;
        try {
            processedAlertEmailRepository.deleteByProcessedAtBefore(
                    java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(PROCESSED_EMAIL_KEEP_DAYS));
        } catch (Exception e) {
            System.err.println("[EmailSync] Could not clean up imported-email records: " + e.getMessage());
        }
    }

    private String getTextFromMessage(Message message) throws MessagingException, IOException {
        if (message.isMimeType("text/plain")) {
            return message.getContent().toString();
        } else if (message.isMimeType("text/html")) {
            return message.getContent().toString();
        } else if (message.isMimeType("multipart/*")) {
            MimeMultipart mimeMultipart = (MimeMultipart) message.getContent();
            return getTextFromMimeMultipart(mimeMultipart);
        }
        return "";
    }

    private String getTextFromMimeMultipart(MimeMultipart mimeMultipart) throws MessagingException, IOException {
        StringBuilder result = new StringBuilder();
        int count = mimeMultipart.getCount();
        for (int i = 0; i < count; i++) {
            BodyPart bodyPart = mimeMultipart.getBodyPart(i);
            if (bodyPart.isMimeType("text/plain")) {
                result.append(bodyPart.getContent());
            } else if (bodyPart.isMimeType("text/html")) {
                result.append(bodyPart.getContent());
            } else if (bodyPart.getContent() instanceof MimeMultipart) {
                result.append(getTextFromMimeMultipart((MimeMultipart) bodyPart.getContent()));
            }
        }
        return result.toString();
    }

    /** Tenders read from one alert email; {@code fullListError} is set when the full list could not be fetched. */
    static final class ParsedEmail {
        final List<Map<String, String>> tenders;
        final String fullListError;

        ParsedEmail(List<Map<String, String>> tenders, String fullListError) {
            this.tenders = tenders;
            this.fullListError = fullListError;
        }
    }

    private List<Map<String, String>> extractTendersFromEmailHtml(String html) {
        return parseAlertEmail(html).tenders;
    }

    /**
     * Reads one alert email: the full list through the email's "View All" link, or, when there is
     * no such link or Tender247 does not answer, the tenders printed in the email itself.
     */
    ParsedEmail parseAlertEmail(String html) {
        String fullListError = null;
        Document doc = Jsoup.parse(html == null ? "" : html);
        Element viewAll = doc.selectFirst("a:contains(View All), a:contains(Fresh Tenders)");
        if (viewAll != null) {
            String key = resolveViewAllKey(viewAll.absUrl("href"));
            if (key != null && !key.isBlank()) {
                try {
                    return new ParsedEmail(fetchTendersFromT247Api(key), null);
                } catch (Exception e) {
                    fullListError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    System.err.println("[EmailSync] Tender247 full list failed (" + fullListError + "); reading the email's own table.");
                }
            }
        }
        return new ParsedEmail(parseTenderTables(doc), fullListError);
    }

    private List<Map<String, String>> parseTenderTables(Document doc) {
        List<Map<String, String>> tenders = new ArrayList<>();
        try {
            // Fallback table parser
            Set<String> seenIds = new HashSet<>();
            Elements tables = doc.select("table");
            for (Element table : tables) {
                // Check if this table has a direct header row for tender details
                Element header = table.selectFirst("> thead > tr");
                if (header == null) {
                    header = table.selectFirst("> tbody > tr, > tr");
                }
                if (header == null) continue;

                Elements headerCells = header.children();
                if (headerCells.size() < 3) continue;

                String c0Hdr = headerCells.get(0).text().toUpperCase();
                String c1Hdr = headerCells.get(1).text().toUpperCase();
                if (!c0Hdr.contains("TENDER DETAILS") || !c1Hdr.contains("ORGANIZATION")) {
                    continue;
                }

                // Genuine tender details table found: collect rows from all direct-child tbody elements
                Elements tbodies = table.select("> tbody");
                Elements rows = new Elements();
                if (!tbodies.isEmpty()) {
                    for (Element tb : tbodies) {
                        rows.addAll(tb.children());
                    }
                } else {
                    rows.addAll(table.children());
                }

                for (Element tr : rows) {
                    if (tr == header) continue;

                    Elements cells = tr.children();
                    if (cells.size() < 3) continue;

                    Element cell0 = cells.get(0);
                    Element cell1 = cells.get(1);
                    Element cell2 = cells.get(2);

                    String cell0Text = cell0.text();
                    Pattern p = Pattern.compile("T247\\s+ID\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
                    Matcher m = p.matcher(cell0Text);
                    if (!m.find()) continue;
                    String id = m.group(1);
                    if (id.isEmpty() || seenIds.contains(id)) continue;

                    Element a = cell0.selectFirst("a");
                    String url = a != null ? a.attr("href") : "";
                    if (url.isEmpty() && a != null) {
                        url = a.absUrl("href");
                    }
                    String title = a != null ? a.text().replaceAll("\\s+", " ").trim() : "";

                    Map<String, String> t = new HashMap<>();
                    t.put("id", id);
                    t.put("url", url);
                    t.put("title", title);

                    // Extract authority and location from cell1
                    Element authEl = cell1.selectFirst("span[style*=bold], strong, b");
                    String authority = "";
                    String location = "";
                    if (authEl != null) {
                        authority = authEl.text().replaceAll("\\s+", " ").trim();
                        location = cell1.text().replace(authEl.text(), "").replaceAll("\\s+", " ").trim();
                    } else {
                        String[] parts = cell1.text().split("\\n");
                        if (parts.length > 1) {
                            authority = parts[0].trim();
                            location = parts[1].trim();
                        } else {
                            authority = cell1.text().trim();
                            location = "N/A";
                        }
                    }
                    t.put("authority", authority);
                    t.put("location", location);

                    // Extract official bid number from title or cell0 text if present
                    String bidNo = TenderClassifier.extractGemBidNumber(title, cell0Text);
                    if (bidNo != null && !bidNo.isBlank()) {
                        t.put("ref_no", bidNo);
                    } else {
                        // The email table only carries the T247 ID. Ask Tender247's detail endpoint for the
                        // Organization Tender ID (and portal / amounts) so non-GeM numbers are not lost.
                        Map<String, String> detail = fetchTenderDetailSafe(id, "", null);
                        String tenderNumber = detail.getOrDefault("tender_number", "").trim();
                        if (!tenderNumber.isEmpty() && !TenderClassifier.isPlaceholderRefNo(tenderNumber)) {
                            t.put("ref_no", tenderNumber);
                        }
                        String website = detail.getOrDefault("nameof_website", "").trim();
                        if (!website.isEmpty()) {
                            t.put("nameof_website", website);
                        }
                        for (String key : List.of("emd_raw", "document_fee_raw")) {
                            if (detail.containsKey(key)) t.put(key, detail.get(key));
                        }
                    }

                    // Extract due date & cost from cell2
                    String cell2Text = cell2.text().replaceAll("\\s+", " ").trim();
                    Matcher dateMatcher = Pattern.compile("\\b(\\d{2}-\\d{2}-\\d{4})\\b").matcher(cell2Text);
                    if (dateMatcher.find()) {
                        t.put("due_date", dateMatcher.group(1));
                    }
                    String costRaw = cell2Text.replaceAll("\\b\\d{2}-\\d{2}-\\d{4}\\b", "").trim();
                    if (!costRaw.isEmpty()) {
                        t.put("estimated_cost_raw", costRaw);
                    }

                    seenIds.add(id);
                    tenders.add(t);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing HTML email body: " + e.getMessage());
        }
        return tenders;
    }

    private List<Map<String, String>> fetchTendersFromT247Api(String key) throws Exception {
        String tokenUrl = "https://t247_api.tender247.com/apigateway/T247ApiTender/api/auth/login/user/key";
        String searchUrl = "https://t247_api.tender247.com/apigateway/T247Tender/mail/api/tender/auth/search-tender";

        Map<String, String> loginPayload = Map.of("key", key.replace(" ", "+"));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "Mozilla/5.0");

        HttpEntity<Map<String, String>> loginRequest = new HttpEntity<>(loginPayload, headers);
        ResponseEntity<JsonNode> loginResponse = restTemplate.postForEntity(tokenUrl, loginRequest, JsonNode.class);
        JsonNode dataNode = loginResponse.getBody().get("Data").get(0);

        String token = dataNode.get("token").asText();
        String userId = dataNode.get("user_id").asText();
        String queryId = dataNode.get("user_query_id").asText();
        String mailDate = dataNode.get("mail_date").asText();

        List<Map<String, String>> tenders = new ArrayList<>();
        // The morning, noon and evening alerts of one search all open that day's full list.
        if (!listsFetchedThisRun.add(userId + "|" + queryId + "|" + mailDate)) {
            System.out.println("[EmailSync] Tender247 list for this search and day was already read in this run.");
            return tenders;
        }

        headers.setBearerAuth(token);

        List<JsonNode> docs = new ArrayList<>();
        for (int page = 1; page <= MAX_LIST_PAGES; page++) {
            Map<String, Object> searchPayload = searchPayload(dataNode.get("user_id").asLong(),
                    dataNode.get("user_query_id").asLong(), mailDate, page);
            HttpEntity<Map<String, Object>> searchRequest = new HttpEntity<>(searchPayload, headers);
            ResponseEntity<JsonNode> searchResponse = restTemplate.postForEntity(searchUrl, searchRequest, JsonNode.class);
            JsonNode pageDocs = searchResponse.getBody() == null ? null : searchResponse.getBody().get("Data");
            int count = pageDocs == null || !pageDocs.isArray() ? 0 : pageDocs.size();
            if (pageDocs != null && pageDocs.isArray()) pageDocs.forEach(docs::add);
            if (count < LIST_PAGE_SIZE) break;
            if (page == MAX_LIST_PAGES) {
                System.out.println("[EmailSync] Tender247 list longer than " + (MAX_LIST_PAGES * LIST_PAGE_SIZE) + " tenders; read the first pages only.");
            }
        }

        for (JsonNode doc : docs) {
            String tenderId = doc.get("tender_id").asText();
            String securityCode = doc.has("security_code") && !doc.get("security_code").isNull() ? doc.get("security_code").asText() : "";
            String spaUrl = String.format("https://www.tender247.com/auth/tender/%s/%s/%s", tenderId, securityCode, userId);

            Map<String, String> t = new HashMap<>();
            t.put("id", tenderId);
            t.put("url", spaUrl);
            t.put("title", doc.has("requirement_workbrief") && !doc.get("requirement_workbrief").isNull() ? doc.get("requirement_workbrief").asText() : "");
            t.put("authority", doc.has("organization_name") && !doc.get("organization_name").isNull() ? doc.get("organization_name").asText() : "");
            t.put("location", doc.has("site_location") && !doc.get("site_location").isNull() ? doc.get("site_location").asText() : "");
            t.put("due_date", doc.has("submission_enddate") && !doc.get("submission_enddate").isNull() ? doc.get("submission_enddate").asText() : "");

            String tenderNumber = doc.has("tender_number") && !doc.get("tender_number").isNull() ? doc.get("tender_number").asText("").trim() : "";
            String nameofWebsite = doc.has("nameof_website") && !doc.get("nameof_website").isNull() ? doc.get("nameof_website").asText("").trim() : "";

            for (String costField : List.of("tender_estimatedcost", "estimatedcost")) {
                if (!t.containsKey("estimated_cost_raw") && doc.has(costField) && !doc.get(costField).isNull()) {
                    t.put("estimated_cost_raw", doc.get(costField).asText());
                }
            }
            if (doc.has("earnest_money_deposite") && !doc.get("earnest_money_deposite").isNull()) {
                t.put("emd_raw", doc.get("earnest_money_deposite").asText());
            }
            if (doc.has("document_fees") && !doc.get("document_fees").isNull()) {
                t.put("document_fee_raw", doc.get("document_fees").asText());
            }

            // Look up the detail page only when the tender number is missing. The list no longer
            // carries the portal name, so asking for it would mean one extra call per tender.
            if (tenderNumber.isEmpty()) {
                Map<String, String> detail = fetchTenderDetailSafe(tenderId, securityCode, token);
                if (tenderNumber.isEmpty() && detail.containsKey("tender_number")) {
                    tenderNumber = detail.get("tender_number");
                }
                if (nameofWebsite.isEmpty() && detail.containsKey("nameof_website")) {
                    nameofWebsite = detail.get("nameof_website");
                }
                if (!t.containsKey("estimated_cost_raw") && detail.containsKey("estimated_cost_raw")) {
                    t.put("estimated_cost_raw", detail.get("estimated_cost_raw"));
                }
                if (!t.containsKey("emd_raw") && detail.containsKey("emd_raw")) {
                    t.put("emd_raw", detail.get("emd_raw"));
                }
                if (!t.containsKey("document_fee_raw") && detail.containsKey("document_fee_raw")) {
                    t.put("document_fee_raw", detail.get("document_fee_raw"));
                }
            }

            if (!tenderNumber.isEmpty()) {
                t.put("ref_no", tenderNumber);
            }
            if (!nameofWebsite.isEmpty()) {
                t.put("nameof_website", nameofWebsite);
            }

            tenders.add(t);
        }

        return tenders;
    }

    /**
     * Body for Tender247's search-tender call. Tender247 now rejects the request unless every
     * filter field is present; empty / 0 / false means "no extra filter", so the list is exactly
     * the alert's saved search for that day.
     */
    static Map<String, Object> searchPayload(long userId, long queryId, String mailDate, int page) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("tab_id", 1);
        p.put("tender_id", 0);
        p.put("page_no", page);
        p.put("record_per_page", LIST_PAGE_SIZE);
        p.put("user_id", userId);
        p.put("user_email_service_query_id", queryId);
        p.put("mail_date", mailDate);
        p.put("tab_status", 3);
        for (String f : List.of("tender_number", "search_text", "refine_search_text", "publication_date_from",
                "publication_date_to", "closing_date_from", "closing_date_to", "statezone_ids", "city_ids", "state_ids",
                "organization_ids", "organization_name", "keyword_id", "mfa", "nameof_website", "product_id",
                "organization_type_id", "sub_industry_id", "quantity")) {
            p.put(f, "");
        }
        for (String f : List.of("tender_value_operator", "tender_value_from", "tender_value_to", "sort_by", "sort_type",
                "tender_typeid", "search_by", "guest_user_id", "msme_exemption", "startup_exemption", "gem",
                "quantity_operator")) {
            p.put(f, 0);
        }
        for (String f : List.of("search_by_location", "is_tender_doc_uploaded", "exact_search", "exact_search_text",
                "search_by_split_word")) {
            p.put(f, false);
        }
        return p;
    }

    private Map<String, String> fetchTenderDetailSafe(String tenderId, String securityCode, String token) {
        if (tenderId == null || tenderId.isBlank()) return Collections.emptyMap();
        try {
            String detailUrl = "https://t247_api.tender247.com/apigateway/T247Tender/api/tender/tender-detail/" + tenderId;
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("User-Agent", "Mozilla/5.0");
            headers.set("Referer", "https://www.tender247.com/");
            if (token != null && !token.isBlank()) {
                headers.setBearerAuth(token);
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("guest_user_id", 0);
            payload.put("security_code", securityCode != null ? securityCode : "");
            payload.put("ip", "");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(detailUrl, request, JsonNode.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null && response.getBody().has("Data")) {
                JsonNode data = response.getBody().get("Data");
                if (data.isArray() && data.size() > 0) {
                    JsonNode detail = data.get(0);
                    Map<String, String> res = new HashMap<>();
                    if (detail.has("tender_number") && !detail.get("tender_number").isNull()) {
                        res.put("tender_number", detail.get("tender_number").asText("").trim());
                    }
                    if (detail.has("nameof_website") && !detail.get("nameof_website").isNull()) {
                        res.put("nameof_website", detail.get("nameof_website").asText("").trim());
                    }
                    if (detail.has("tender_estimatedcost") && !detail.get("tender_estimatedcost").isNull()) {
                        res.put("estimated_cost_raw", detail.get("tender_estimatedcost").asText());
                    }
                    if (detail.has("earnest_money_deposite") && !detail.get("earnest_money_deposite").isNull()) {
                        res.put("emd_raw", detail.get("earnest_money_deposite").asText());
                    }
                    if (detail.has("document_fees") && !detail.get("document_fees").isNull()) {
                        res.put("document_fee_raw", detail.get("document_fees").asText());
                    }
                    return res;
                }
            }
        } catch (Exception e) {
            System.out.println(String.format("[T247Detail] Warning: Failed to fetch detail for tender %s: %s", tenderId, e.getMessage()));
        }
        return Collections.emptyMap();
    }

    /**
     * The "View All" link is a Tender247 click-tracking address (r.tenders.bidsnrfp.com/...) that
     * redirects to bidsnrfp.com/MailTenders?key=... . Follows the redirects (without loading any
     * page) until the key appears, the same way clicking the link would.
     */
    String resolveViewAllKey(String href) {
        String url = href;
        for (int hop = 0; hop <= 5 && url != null; hop++) {
            String key = extractT247Key(url);
            if (key != null && !key.isBlank()) return key;
            if (hop == 5) break;
            url = nextRedirect(url);
        }
        return null;
    }

    /** The Location a URL redirects to, or null when it does not redirect. */
    String nextRedirect(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null;
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) URI.create(url).toURL().openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            String location = c.getHeaderField("Location");
            if (code / 100 != 3 || location == null || location.isBlank()) return null;
            return URI.create(url).resolve(location.trim()).toString();
        } catch (Exception e) {
            System.err.println("[EmailSync] Could not open the alert's View All link: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    public static String extractT247Key(String href) {
        if (href == null || !href.contains("key=")) return null;
        int start = href.indexOf("key=") + 4;
        int end = href.indexOf('&', start);
        String rawKey = (end != -1) ? href.substring(start, end) : href.substring(start);
        try {
            return java.net.URLDecoder.decode(rawKey, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return rawKey;
        }
    }

    private boolean isLegitimateT247Match(Tender existing, String t247Id, String refNo) {
        if (existing == null) return false;
        // 1. Authoritative official bid number matches
        if (!TenderClassifier.isPlaceholderRefNo(refNo) && !TenderClassifier.isPlaceholderRefNo(existing.getRefNo())) {
            if (refNo.equalsIgnoreCase(existing.getRefNo())) {
                return true;
            }
        }
        // 2. The tender already carries this T247 ID (source_id only ever holds T247 IDs, whichever
        //    portal the tender first came from)
        if (isT247Id(t247Id) && existing.getSourceId() != null && t247Id.equals(existing.getSourceId().trim())) {
            return true;
        }
        // 3. Same source namespace ("Tender247")
        if ("Tender247".equalsIgnoreCase(existing.getSource())) {
            if (t247Id != null && (t247Id.equalsIgnoreCase(existing.getId()) || t247Id.equalsIgnoreCase(existing.getSourceId()))) {
                return true;
            }
        }
        // If existing record belongs to another source (e.g. GeM) and refNo does not match, reject match
        return false;
    }

    /** Tender247's own tender number (T247 ID) is always numeric. */
    static boolean isT247Id(String value) {
        return value != null && value.trim().matches("^\\d{1,50}$");
    }

    void enrichExistingRecordFromTender247(Tender existing, Map<String, String> tData, String refNo, String link) {
        // 1. Official reference / bid number (Organization Tender ID)
        if ((existing.getRefNo() == null || existing.getRefNo().isBlank() || TenderClassifier.isPlaceholderRefNo(existing.getRefNo()))
                && !TenderClassifier.isPlaceholderRefNo(refNo)) {
            existing.setRefNo(refNo);
        }

        // 1b. T247 ID. A tender that first came from GeM (or was added by hand) has none yet, so save it now.
        //     A T247 ID already on the tender is kept.
        String t247Id = tData.get("id");
        if (isT247Id(t247Id) && !isT247Id(existing.getSourceId())) {
            existing.setSourceId(t247Id.trim());
        }

        // 2. Tender type is not decided here: the Tender entity sets it from the Organization Tender ID (ref_no).

        // 3. Original URL: fill it only when the tender has none yet
        if ((existing.getOriginalUrl() == null || existing.getOriginalUrl().isBlank()) && link != null && !link.isBlank()) {
            existing.setOriginalUrl(link);
        }

        // 4. Metadata non-destructive enrichment: do not overwrite populated values with blank/null
        if (existing.getAuthority() == null || existing.getAuthority().isBlank()) {
            existing.setAuthority(tData.getOrDefault("authority", ""));
        }
        if (existing.getLocation() == null || existing.getLocation().isBlank() || "N/A".equalsIgnoreCase(existing.getLocation())) {
            existing.setLocation(tData.getOrDefault("location", "N/A"));
        }
        if (existing.getDueDate() == null || existing.getDueDate().isBlank()) {
            existing.setDueDate(tData.getOrDefault("due_date", ""));
        }
        if ((existing.getEstimatedCostRaw() == null || "N/A".equalsIgnoreCase(existing.getEstimatedCostRaw())) && tData.containsKey("estimated_cost_raw")) {
            existing.setEstimatedCostRaw(tData.get("estimated_cost_raw"));
        }
        if ((existing.getEmdRaw() == null || "N/A".equalsIgnoreCase(existing.getEmdRaw())) && tData.containsKey("emd_raw")) {
            existing.setEmdRaw(tData.get("emd_raw"));
        }
        if ((existing.getDocumentFeeRaw() == null || "N/A".equalsIgnoreCase(existing.getDocumentFeeRaw())) && tData.containsKey("document_fee_raw")) {
            existing.setDocumentFeeRaw(tData.get("document_fee_raw"));
        }
        if ((existing.getSector() == null || existing.getSector().isBlank()) && tData.containsKey("nameof_website")) {
            existing.setSector(tData.get("nameof_website"));
        }
    }
}
