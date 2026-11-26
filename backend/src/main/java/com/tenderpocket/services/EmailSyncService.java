package com.tenderpocket.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import com.tenderpocket.utils.TenderClassifier;
import com.tenderpocket.utils.UnderscoreHostHttps;
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

    // Tender247's API host (t247_api.tender247.com) contains "_", which Java's default HTTPS check rejects.
    private final RestTemplate restTemplate = new RestTemplate(UnderscoreHostHttps.requestFactory(15_000, 60_000));
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String BROWSER_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");
    private static final int T247_PAGE_SIZE = 100;
    /** Safety ceiling on pages of one alert list (2,000 tenders); the largest lists seen hold a few hundred. */
    private static final int T247_MAX_PAGES = 20;

    /**
     * Follows an alert email's "view all" tracking link to the Tender247 address it redirects to. A field so
     * tests can replace the network call.
     */
    java.util.function.Function<String, String> redirectFollower = EmailSyncService::followRedirects;

    /** What one alert email yielded. */
    static final class EmailTenders {
        final List<Map<String, String>> tenders = new ArrayList<>();
        /** The tenders came from the email's full "view all" list, not from the few rows printed in it. */
        boolean fromFullList;
        /** The same daily list was already read from an earlier email in this run. */
        boolean sameListAsEarlierEmail;
        /** The email has a full list but it could not be read; the printed rows were used and it should be retried. */
        boolean fullListFailed;
    }

    private Store connectStore() throws Exception {
        Properties properties = new Properties();
        properties.put("mail.store.protocol", "imaps");
        properties.put("mail.imaps.host", imapHost);
        properties.put("mail.imaps.port", String.valueOf(imapPort));
        properties.put("mail.imaps.ssl.enable", "true");
        properties.put("mail.imaps.connectiontimeout", "5000");
        properties.put("mail.imaps.timeout", "5000");

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
        return store;
    }

    public int syncEmails() throws Exception {
        int tendersImported = 0;
        if (imapUsername == null || imapUsername.isEmpty() || imapPassword == null || imapPassword.isEmpty()) {
            System.out.println("[EmailSync] IMAP credentials not configured. Skipping email sync.");
            return 0;
        }

        System.out.println(String.format("Connecting to IMAP mail server at %s:%d...", imapHost, imapPort));

        Store store = connectStore();

        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            // The morning and noon alerts of a category share one daily list; read it once per run.
            Set<String> listsRead = new HashSet<>();

            LocalDate todayIST = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
            LocalDate cutoffDate = todayIST.minusDays(1); // Last 2 days: today and yesterday
            Date cutoffDateObj = Date.from(cutoffDate.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant());

            SearchTerm unreadTerm = new FlagTerm(new Flags(Flags.Flag.SEEN), false);
            SearchTerm searchTerm = unreadTerm;
            if (senderFilter != null && !senderFilter.isEmpty()) {
                searchTerm = new AndTerm(unreadTerm, new FromStringTerm(senderFilter));
            }

            try {
                SearchTerm dateTerm = new ReceivedDateTerm(ComparisonTerm.GE, cutoffDateObj);
                searchTerm = new AndTerm(searchTerm, dateTerm);
            } catch (Exception ignored) {}

            Message[] messages;
            try {
                messages = inbox.search(searchTerm);
            } catch (Exception e) {
                System.out.println("[EmailSync] IMAP search with date term failed (" + e.getMessage() + "). Falling back to unread term search.");
                SearchTerm fallbackSearch = unreadTerm;
                if (senderFilter != null && !senderFilter.isEmpty()) {
                    fallbackSearch = new AndTerm(unreadTerm, new FromStringTerm(senderFilter));
                }
                messages = inbox.search(fallbackSearch);
            }

            System.out.println(String.format("Found %d unread message(s) matching filter. Processing only last 2 days (since %s IST)...", messages.length, cutoffDate));

            for (Message msg : messages) {
                String subject = msg.getSubject();
                String from = (msg.getFrom() != null && msg.getFrom().length > 0) ? msg.getFrom()[0].toString() : "Unknown";

                // Enforce 2-day cutoff (Asia/Kolkata) on message received / sent date
                Date msgDate = msg.getReceivedDate() != null ? msg.getReceivedDate() : msg.getSentDate();
                if (msgDate != null) {
                    LocalDate emailLocalDate = msgDate.toInstant().atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate();
                    if (emailLocalDate.isBefore(cutoffDate)) {
                        System.out.println(String.format("Email \"%s\" from %s dated %s is older than 2-day cutoff (%s). Marking as read and skipping.",
                                subject, from, emailLocalDate, cutoffDate));
                        msg.setFlag(Flags.Flag.SEEN, true);
                        continue;
                    }
                }

                System.out.println(String.format("Processing email: %s from %s", subject, from));

                if (senderFilter != null && !senderFilter.isEmpty() && !from.toLowerCase().contains(senderFilter.toLowerCase())) {
                    System.out.println("Email does not match sender filter. Skipping.");
                    continue;
                }

                String content = getTextFromMessage(msg);
                EmailTenders email = readAlertEmail(content, listsRead);
                int[] counts = importTenders(email.tenders, false);
                tendersImported += counts[0];
                if (email.sameListAsEarlierEmail) {
                    System.out.println("[EmailSync] Same daily list as an earlier email in this run; nothing more to read.");
                } else {
                    System.out.println(String.format("[EmailSync] %s: %d tender(s) in the email, %d new, %d already present.",
                            email.fromFullList ? "Full \"view all\" list" : "Rows printed in the email",
                            email.tenders.size(), counts[0], counts[1]));
                }

                if (email.fullListFailed) {
                    // Left unread so the next sync reads the full list; only the printed rows came in this time.
                    System.out.println("[EmailSync] The full list of this email could not be read. Leaving it unread to retry.");
                } else {
                    msg.setFlag(Flags.Flag.SEEN, true);
                }
            }

            inbox.close(true);
        } finally {
            store.close();
            System.out.println("Disconnected from IMAP store.");
        }

        return tendersImported;
    }

    /**
     * Saves the tenders that are not in the database yet and fills gaps on those that are.
     * Returns {new, already present, skipped as past their due date}.
     */
    int[] importTenders(List<Map<String, String>> tenders, boolean skipExpired) {
        int imported = 0, alreadyPresent = 0, expired = 0;
        LocalDate today = LocalDate.now(INDIA);
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
                alreadyPresent++;
                continue;
            }

            if (skipExpired && isPastDue(tData.get("due_date"), today)) {
                expired++;
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
            imported++;
        }
        return new int[]{imported, alreadyPresent, expired};
    }

    /** True when the due date can be read and is before today. Dates in a format not recognised count as not past. */
    static boolean isPastDue(String dueDate, LocalDate today) {
        if (dueDate == null) return false;
        Matcher iso = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})").matcher(dueDate);
        Matcher indian = Pattern.compile("(\\d{2})[-/](\\d{2})[-/](\\d{4})").matcher(dueDate);
        try {
            if (iso.find()) {
                return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3))).isBefore(today);
            }
            if (indian.find()) {
                return LocalDate.of(Integer.parseInt(indian.group(3)), Integer.parseInt(indian.group(2)), Integer.parseInt(indian.group(1))).isBefore(today);
            }
        } catch (Exception ignored) {
            // not a real date
        }
        return false;
    }

    /**
     * One-time catch-up: reads the full "view all" list of every alert email of the last {@code days} days,
     * read or unread, and imports the tenders that are missing. The mailbox is opened read-only, so no email
     * changes its read state. Returns {lists read, new, already present, skipped as past due, lists that failed}.
     */
    public int[] catchUpFromAlertEmails(int days, boolean skipExpired) throws Exception {
        if (imapUsername == null || imapUsername.isEmpty() || imapPassword == null || imapPassword.isEmpty()) {
            System.out.println("[EmailCatchUp] IMAP credentials not configured. Nothing to do.");
            return new int[5];
        }
        int lists = 0, imported = 0, present = 0, expired = 0, failed = 0;
        Store store = connectStore();
        try {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            Date since = Date.from(LocalDate.now(INDIA).minusDays(days).atStartOfDay(INDIA).toInstant());
            SearchTerm term = new ReceivedDateTerm(ComparisonTerm.GE, since);
            if (senderFilter != null && !senderFilter.isEmpty()) {
                term = new AndTerm(term, new FromStringTerm(senderFilter));
            }
            Message[] messages = inbox.search(term);
            System.out.println(String.format("[EmailCatchUp] %d email(s) since %s to check for alert lists.",
                    messages.length, LocalDate.now(INDIA).minusDays(days)));
            Set<String> listsRead = new HashSet<>();
            // Newest first, so tenders that are still open come in before older ones.
            for (int i = messages.length - 1; i >= 0; i--) {
                try {
                    EmailTenders email = readAlertEmail(getTextFromMessage(messages[i]), listsRead);
                    if (email.fullListFailed) failed++;
                    if (!email.fromFullList || email.sameListAsEarlierEmail) continue;
                    int[] counts = importTenders(email.tenders, skipExpired);
                    lists++;
                    imported += counts[0];
                    present += counts[1];
                    expired += counts[2];
                    System.out.println(String.format("[EmailCatchUp] List %d: %d tender(s), %d new, %d already present, %d past due.",
                            lists, email.tenders.size(), counts[0], counts[1], counts[2]));
                } catch (Exception e) {
                    failed++;
                    System.out.println("[EmailCatchUp] Skipping one email: " + e.getMessage());
                }
            }
            inbox.close(false);
        } finally {
            store.close();
        }
        System.out.println(String.format(
                "[EmailCatchUp] Done: %d list(s) read, %d tender(s) imported, %d already present, %d past due, %d list(s) failed.",
                lists, imported, present, expired, failed));
        return new int[]{lists, imported, present, expired, failed};
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

    private List<Map<String, String>> extractTendersFromEmailHtml(String html) {
        return readAlertEmail(html, null).tenders;
    }

    /**
     * Reads the tenders of one alert email. An alert prints only a few tenders and links to the full list
     * ("Click here to view all (179) Fresh Tenders"); the full list is read when the link leads to one, and
     * the printed rows are the fallback.
     */
    EmailTenders readAlertEmail(String html, Set<String> listsAlreadyRead) {
        EmailTenders result = new EmailTenders();
        List<Map<String, String>> tenders = result.tenders;
        boolean fullListWasEmpty = false;
        try {
            Document doc = Jsoup.parse(html);
            // Search for Tender247 View All link or parse tables directly
            Element viewAll = doc.selectFirst("a:contains(View All), a:contains(Fresh Tenders)");
            if (viewAll != null) {
                String href = viewAll.absUrl("href");
                if (href.isEmpty()) href = viewAll.attr("href");
                try {
                    String key = resolveT247Key(href);
                    if (key != null && !key.isBlank()) {
                        List<Map<String, String>> fullList = fetchTendersFromT247Api(key, listsAlreadyRead);
                        if (fullList == null) {
                            result.fromFullList = true;
                            result.sameListAsEarlierEmail = true;
                            return result;
                        }
                        if (!fullList.isEmpty()) {
                            tenders.addAll(fullList);
                            result.fromFullList = true;
                            return result;
                        }
                        fullListWasEmpty = true;
                    }
                } catch (Exception e) {
                    result.fullListFailed = true;
                    System.out.println("[EmailSync] Could not read the full list behind \"view all\": " + e.getMessage());
                }
            }

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
        // Tender247 returned an empty list although the email prints tenders: treat it as a failed read.
        if (fullListWasEmpty && !tenders.isEmpty()) {
            result.fullListFailed = true;
        }
        return result;
    }

    /**
     * The Tender247 key of a "view all" link. The link in an alert email is a tracking redirect, so when the
     * key is not in the link itself the redirect is followed to the Tender247 address that carries it.
     * Returns null when the link does not lead to a list; throws when the link could not be followed.
     */
    String resolveT247Key(String href) {
        String key = extractT247Key(href);
        if (key != null && !key.isBlank()) return key;
        if (href == null || !href.toLowerCase().startsWith("http")) return null;
        return extractT247Key(redirectFollower.apply(href));
    }

    static String followRedirects(String href) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(href))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.discarding()).uri().toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while following the link", e);
        } catch (Exception e) {
            throw new IllegalStateException("the link could not be followed (" + e.getMessage() + ")", e);
        }
    }

    /** The request Tender247's own page sends for an alert list. A shorter body is answered with no tenders. */
    private static Map<String, Object> t247ListRequest(String userId, String queryId, String mailDate, int page) {
        Map<String, Object> body = new HashMap<>();
        for (String field : List.of("tender_number", "search_text", "refine_search_text", "publication_date_from",
                "publication_date_to", "closing_date_from", "closing_date_to", "statezone_ids", "city_ids", "state_ids",
                "organization_ids", "organization_name", "keyword_id", "mfa", "nameof_website", "product_id",
                "organization_type_id", "sub_industry_id", "quantity")) {
            body.put(field, "");
        }
        for (String field : List.of("tender_id", "tender_value_operator", "tender_value_from", "tender_value_to",
                "tender_typeid", "search_by", "guest_user_id", "quantity_operator", "msme_exemption",
                "startup_exemption", "gem", "boq")) {
            body.put(field, 0);
        }
        for (String field : List.of("search_by_location", "is_tender_doc_uploaded", "exact_search", "exact_search_text",
                "search_by_split_word", "is_ai_summary", "is_grace", "surety_bond", "limited_tender")) {
            body.put(field, false);
        }
        body.put("tab_id", 1);
        body.put("sort_by", 1);
        body.put("sort_type", 2);
        body.put("tab_status", 3);
        body.put("page_no", page);
        body.put("record_per_page", T247_PAGE_SIZE);
        body.put("user_id", userId);
        body.put("user_email_service_query_id", queryId);
        body.put("mail_date", mailDate);
        return body;
    }

    private List<Map<String, String>> fetchTendersFromT247Api(String key) throws Exception {
        return fetchTendersFromT247Api(key, null);
    }

    /**
     * Every tender of the alert list a key opens, read page by page. Returns null when {@code listsAlreadyRead}
     * shows the same daily list was read before (the morning and noon alerts of a category share one).
     */
    private List<Map<String, String>> fetchTendersFromT247Api(String key, Set<String> listsAlreadyRead) throws Exception {
        String tokenUrl = "https://t247_api.tender247.com/apigateway/T247ApiTender/api/auth/login/user/key";
        String searchUrl = "https://t247_api.tender247.com/apigateway/T247Tender/mail/api/tender/auth/search-tender";

        Map<String, String> loginPayload = Map.of("key", key.replace(" ", "+"));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("User-Agent", "Mozilla/5.0");
        headers.set("Referer", "https://www.tender247.com/");

        HttpEntity<Map<String, String>> loginRequest = new HttpEntity<>(loginPayload, headers);
        ResponseEntity<JsonNode> loginResponse = restTemplate.postForEntity(tokenUrl, loginRequest, JsonNode.class);
        JsonNode dataNode = loginResponse.getBody().get("Data").get(0);

        String token = dataNode.get("token").asText();
        String userId = dataNode.get("user_id").asText();
        String queryId = dataNode.get("user_query_id").asText();
        String mailDate = dataNode.get("mail_date").asText();

        if (listsAlreadyRead != null && !listsAlreadyRead.add(queryId + "|" + mailDate)) {
            return null;
        }

        headers.setBearerAuth(token);

        List<Map<String, String>> tenders = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int page = 1; page <= T247_MAX_PAGES; page++) {
          HttpEntity<Map<String, Object>> searchRequest = new HttpEntity<>(t247ListRequest(userId, queryId, mailDate, page), headers);
          ResponseEntity<JsonNode> searchResponse = restTemplate.postForEntity(searchUrl, searchRequest, JsonNode.class);
          JsonNode docs = searchResponse.getBody() == null ? null : searchResponse.getBody().get("Data");
          if (docs == null || !docs.isArray() || docs.size() == 0) break;
          for (JsonNode doc : docs) {
            String tenderId = doc.get("tender_id").asText();
            if (!seenIds.add(tenderId)) continue;
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

            if (doc.has("tender_estimatedcost") && !doc.get("tender_estimatedcost").isNull()) {
                t.put("estimated_cost_raw", doc.get("tender_estimatedcost").asText());
            }
            if (doc.has("earnest_money_deposite") && !doc.get("earnest_money_deposite").isNull()) {
                t.put("emd_raw", doc.get("earnest_money_deposite").asText());
            }
            if (doc.has("document_fees") && !doc.get("document_fees").isNull()) {
                t.put("document_fee_raw", doc.get("document_fees").asText());
            }

            // If tender_number or nameof_website is missing, fetch from tender-detail endpoint safely.
            // A tender already in the database does not need the extra request.
            if ((tenderNumber.isEmpty() || nameofWebsite.isEmpty()) && !tenderRepository.existsBySourceId(tenderId)) {
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
          if (docs.size() < T247_PAGE_SIZE) break;
        }

        return tenders;
    }

    /**
     * The Organization Tender ID (Tender247's tender_number) for a T247 ID, or null when Tender247 has none.
     * Unlike fetchTenderDetailSafe, a failed request is thrown so callers can tell it from a missing number.
     */
    public String fetchOrganizationTenderId(String t247Id) {
        JsonNode detail = requestTenderDetail(t247Id, "", null);
        if (detail == null || !detail.has("tender_number") || detail.get("tender_number").isNull()) return null;
        String tenderNumber = detail.get("tender_number").asText("").trim();
        return TenderClassifier.isPlaceholderRefNo(tenderNumber) ? null : tenderNumber;
    }

    private JsonNode requestTenderDetail(String tenderId, String securityCode, String token) {
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
            if (data.isArray() && data.size() > 0) return data.get(0);
        }
        return null;
    }

    private Map<String, String> fetchTenderDetailSafe(String tenderId, String securityCode, String token) {
        if (tenderId == null || tenderId.isBlank()) return Collections.emptyMap();
        try {
            JsonNode detail = requestTenderDetail(tenderId, securityCode, token);
            if (detail != null) {
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
        } catch (Exception e) {
            System.out.println(String.format("[T247Detail] Warning: Failed to fetch detail for tender %s: %s", tenderId, e.getMessage()));
        }
        return Collections.emptyMap();
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
