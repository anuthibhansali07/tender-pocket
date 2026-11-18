package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tenderpocket.models.Tender;
import com.tenderpocket.repositories.TenderRepository;
import com.tenderpocket.services.ObjectStorageService;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads a state-portal tender's documents after an executive types the portal's CAPTCHA.
 *
 * <p>Flow: {@link #start} opens the tender on its portal in a fresh session and follows "Download
 * as zip file"; the portal answers with its CAPTCHA page, whose image is handed to the app. The
 * session is held (10 minutes) until {@link #submit} posts the executive's answer in that same
 * session. The returned zip is unpacked into {@code <upload-dir>/<tenderId>/}, uploaded to S3 when
 * enabled, and listed in {@code downloaded_docs}, the same storage and listing GeM bid documents use,
 * so the Document Repository card and the spec workflow pick them up unchanged.
 *
 * <p>Nothing here solves CAPTCHAs; a person always types the answer.
 */
@Service
public class StateDocumentService {

    private static final Duration PENDING_TTL = Duration.ofMinutes(10);
    private static final long MAX_TOTAL_BYTES = 300L * 1024 * 1024;

    @Autowired
    private TenderRepository tenderRepository;

    @Autowired
    private StateTenderSyncService stateTenderSyncService;

    @Autowired(required = false)
    private ObjectStorageService objectStorageService;

    @Value("${aws.s3.enabled:false}")
    private boolean awsS3Enabled;

    @Value("${file.upload-dir:public/documents}")
    private String uploadDir = "public/documents";

    @Value("${state.sync.document-request-pause-ms:700}")
    private long requestPauseMs = 700;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** Result returned to the app. */
    public record FetchResult(String status, String token, String captchaImage, String message,
                              List<Map<String, String>> files, String downloadedDocs, String documentUrl) {

        static FetchResult captcha(String token, String image, String message) {
            return new FetchResult("CAPTCHA_REQUIRED", token, image, message, List.of(), null, null);
        }

        static FetchResult error(String message) {
            return new FetchResult("ERROR", null, null, message, List.of(), null, null);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("success", !"ERROR".equals(status));
            m.put("status", status);
            if (token != null) m.put("token", token);
            if (captchaImage != null) m.put("captchaImage", captchaImage);
            if (message != null) m.put("message", message);
            m.put("files", files);
            if (downloadedDocs != null) m.put("downloadedDocs", downloadedDocs);
            if (documentUrl != null) m.put("documentUrl", documentUrl);
            if ("ERROR".equals(status)) m.put("error", message);
            return m;
        }
    }

    private static final class Pending {
        final String tenderId;
        final GepnicSession session;
        final String zipUrl;
        volatile CaptchaForm form;
        final Instant expiresAt = Instant.now().plus(PENDING_TTL);
        // Next-generation NIC portals (Manipur): one CAPTCHA per document unit.
        GepnicPortal portal;
        String workItemId;
        List<NgGepnicLatestCrawler.DocUnit> units = List.of();
        int next;
        final List<GepnicSession.RawResponse> collected = new ArrayList<>();

        Pending(String tenderId, GepnicSession session, String zipUrl, CaptchaForm form) {
            this.tenderId = tenderId;
            this.session = session;
            this.zipUrl = zipUrl;
            this.form = form;
        }

        boolean isNextGen() {
            return portal != null && "nggepnic".equals(portal.kind());
        }
    }

    /** The portal's CAPTCHA form: where to post, which fields to send, and the image to show. */
    record CaptchaForm(String action, List<Map.Entry<String, String>> fields, String answerField, String imageSrc, String pageUrl) {}

    /** True when this tender came from a configured state/central portal whose documents can be fetched. */
    public boolean supports(Tender t) {
        return t != null && portalFor(t).isPresent();
    }

    public FetchResult start(String tenderId) {
        purgeExpired();
        Optional<Tender> opt = tenderRepository.findById(tenderId);
        if (opt.isEmpty()) return FetchResult.error("Tender not found");
        Tender tender = opt.get();
        Optional<GepnicPortal> portalOpt = portalFor(tender);
        if (portalOpt.isEmpty()) {
            return FetchResult.error("Documents can only be fetched for tenders imported from a state eProcurement portal");
        }
        GepnicPortal portal = portalOpt.get();
        if (!portal.isGepnic()) return startNonNic(tender, portal);
        String gepnicId = firstNonBlank(tender.getEprocurementPortalId());
        if (gepnicId == null) return FetchResult.error("This tender has no portal Tender ID");

        pending.values().removeIf(p -> p.tenderId.equals(tenderId));
        GepnicSession session = new GepnicSession(requestPauseMs, Duration.ofSeconds(180));
        try {
            String detailUrl = locateDetailPage(session, portal, tender, gepnicId);
            if (detailUrl == null) {
                return FetchResult.error("Could not find Tender ID " + gepnicId + " on the " + portal.name()
                        + " portal. It may have closed or been withdrawn.");
            }
            Document detail = session.get(detailUrl);
            String zipUrl = findZipLink(detail);
            if (zipUrl == null) return FetchResult.error("The portal page for this tender has no document download link");

            GepnicSession.RawResponse resp = session.getRaw(zipUrl);
            logResponse(tenderId, "document link", resp);
            if (!resp.isHtml()) {
                // Some portals serve the documents without a CAPTCHA.
                return store(tender, resp);
            }
            CaptchaForm form = parseCaptchaForm(resp.asDocument());
            if (form == null) return FetchResult.error("The portal did not return its document CAPTCHA page");
            String token = UUID.randomUUID().toString();
            pending.put(token, new Pending(tenderId, session, zipUrl, form));
            return FetchResult.captcha(token, imageDataUri(session, form.imageSrc()),
                    "Type the characters shown to download the documents from the " + portal.name() + " portal.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.error("Interrupted");
        } catch (Exception e) {
            return FetchResult.error("Could not reach the " + portal.name() + " portal: " + e.getMessage());
        }
    }

    /**
     * Non-NIC state systems. AP/Telangana, Gujarat, Bihar, Chhattisgarh and Karnataka serve documents
     * without a server CAPTCHA, so they are downloaded straight away. Manipur's next-generation NIC
     * portal asks for a CAPTCHA per document unit, which the executive types as for NIC portals.
     */
    private FetchResult startNonNic(Tender tender, GepnicPortal portal) {
        pending.values().removeIf(p -> p.tenderId.equals(tender.getId()));
        GepnicSession session = new GepnicSession(requestPauseMs, Duration.ofSeconds(180));
        String portalId = firstNonBlank(tender.getEprocurementPortalId());
        try {
            List<GepnicSession.RawResponse> files;
            switch (portal.kind()) {
                case "apts" -> files = new ApTsEprocCrawler().downloadDocuments(portal, session, tender.getPortalDocRef());
                case "nprocure" -> files = new NprocureCrawler().downloadDocuments(portal, session, portalId);
                case "eproc2" -> files = new BiharEproc2Crawler().downloadDocuments(portal, session, portalId);
                case "chips" -> files = new ChipsCrawler().downloadDocuments(portal, session, tender.getPortalDocRef());
                case "kppp" -> files = new KpppCrawler().downloadDocuments(portal, session,
                        firstNonBlank(tender.getPortalDocRef(), portalId));
                case "nggepnic" -> {
                    return startNextGen(tender, portal, session);
                }
                default -> {
                    return FetchResult.error("Document download is not set up for the " + portal.name() + " portal");
                }
            }
            if (files.isEmpty()) {
                return FetchResult.error("The " + portal.name() + " portal lists no downloadable documents for this tender"
                        + " (some portals only release them to logged-in bidders).");
            }
            return store(tender, files);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FetchResult.error("Interrupted");
        } catch (Exception e) {
            return FetchResult.error("Could not download from the " + portal.name() + " portal: " + e.getMessage());
        }
    }

    private FetchResult startNextGen(Tender tender, GepnicPortal portal, GepnicSession session) throws IOException, InterruptedException {
        String workItemId = tender.getPortalDocRef();
        if (workItemId == null || workItemId.isBlank()) {
            return FetchResult.error("This tender has no portal document reference; it needs to be re-imported");
        }
        NgGepnicLatestCrawler ng = new NgGepnicLatestCrawler();
        List<NgGepnicLatestCrawler.DocUnit> units = NgGepnicLatestCrawler.documentUnits(ng.details(portal, session, workItemId));
        if (units.isEmpty()) return FetchResult.error("The " + portal.name() + " portal lists no documents for this tender");
        Pending p = new Pending(tender.getId(), session, null, null);
        p.portal = portal;
        p.workItemId = workItemId;
        p.units = units;
        String token = UUID.randomUUID().toString();
        pending.put(token, p);
        return FetchResult.captcha(token, ng.captchaImage(portal, session), nextGenPrompt(p, null));
    }

    private static String nextGenPrompt(Pending p, String prefix) {
        NgGepnicLatestCrawler.DocUnit u = p.units.get(p.next);
        String step = p.units.size() > 1 ? " (" + (p.next + 1) + " of " + p.units.size() + ")" : "";
        return (prefix == null ? "" : prefix + " ") + "Type the characters to download " + u.label() + step
                + " from the " + p.portal.name() + " portal.";
    }

    private FetchResult submitNextGen(Tender tender, String token, Pending p, String answer) throws IOException, InterruptedException {
        NgGepnicLatestCrawler ng = new NgGepnicLatestCrawler();
        NgGepnicLatestCrawler.DocUnit unit = p.units.get(p.next);
        GepnicSession.RawResponse file = ng.download(p.portal, p.session, p.workItemId, unit, answer.trim());
        if (file == null) {
            return FetchResult.captcha(token, ng.captchaImage(p.portal, p.session),
                    nextGenPrompt(p, "The portal said that CAPTCHA was wrong."));
        }
        p.collected.add(file);
        p.next++;
        if (p.next < p.units.size()) {
            return FetchResult.captcha(token, ng.captchaImage(p.portal, p.session),
                    nextGenPrompt(p, "Downloaded " + unit.label() + "."));
        }
        pending.remove(token);
        return store(tender, p.collected);
    }

    public FetchResult submit(String tenderId, String token, String answer) {
        Pending p = token == null ? null : pending.get(token);
        if (p == null || !p.tenderId.equals(tenderId) || Instant.now().isAfter(p.expiresAt)) {
            if (token != null) pending.remove(token);
            return FetchResult.error("This CAPTCHA has expired. Load a new one.");
        }
        if (answer == null || answer.isBlank()) return FetchResult.error("Enter the CAPTCHA characters");
        Optional<Tender> opt = tenderRepository.findById(tenderId);
        if (opt.isEmpty()) return FetchResult.error("Tender not found");
        if (p.isNextGen()) {
            synchronized (p) {
                try {
                    return submitNextGen(opt.get(), token, p, answer);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return FetchResult.error("Interrupted");
                } catch (Exception e) {
                    return FetchResult.error("Download failed: " + e.getMessage());
                }
            }
        }

        synchronized (p) {
            try {
                CaptchaForm form = p.form;
                List<Map.Entry<String, String>> fields = new ArrayList<>();
                for (Map.Entry<String, String> f : form.fields()) {
                    fields.add(f.getKey().equals(form.answerField())
                            ? new AbstractMap.SimpleEntry<>(f.getKey(), answer.trim()) : f);
                }
                GepnicSession.RawResponse resp = p.session.postForm(form.action(), fields, form.pageUrl());
                logResponse(tenderId, "captcha POST", resp);
                Document acceptedPage = null;
                String lastFileUrl = null;
                if (resp.isHtml()) {
                    Document page = resp.asDocument();
                    CaptchaForm again = parseCaptchaForm(page);
                    boolean rejected = again != null && mentionsCaptchaError(page);
                    if (again == null || !rejected) {
                        // Accepted: the portal went back to a page with download links. Tapestry link ids
                        // can change between pages, so prefer the zip link on the page it just returned.
                        String freshZip = again == null ? findZipLink(page) : null;
                        String target = freshZip != null ? freshZip : p.zipUrl;
                        if (again == null) acceptedPage = page;
                        lastFileUrl = target;
                        GepnicSession.RawResponse file = p.session.getRaw(target);
                        logResponse(tenderId, "download after captcha (" + (freshZip != null ? "fresh link" : "original link") + ")", file);
                        if (!file.isHtml()) {
                            resp = file;
                            again = null;
                        } else {
                            Document filePage = file.asDocument();
                            CaptchaForm next = parseCaptchaForm(filePage);
                            if (next != null) again = next;
                            resp = file;
                        }
                    }
                    if (resp.isHtml() && again != null) {
                        p.form = again;
                        return FetchResult.captcha(token, imageDataUri(p.session, again.imageSrc()),
                                rejected ? "The portal said that CAPTCHA was wrong. Try the new one."
                                        : "The portal asked for a CAPTCHA again. Type the new one.");
                    }
                }
                if (resp.isHtml()) {
                    return FetchResult.error("The portal accepted the CAPTCHA but did not send the documents. Try again.");
                }
                pending.remove(token);
                List<GepnicSession.RawResponse> downloads = new ArrayList<>();
                downloads.add(resp);
                // The zip link only bundles one section of the page (usually the work item documents),
                // so also fetch the NIT and individual document links while the session is unlocked.
                if (acceptedPage != null) downloads.addAll(downloadRemainingDocuments(tenderId, p.session, acceptedPage, lastFileUrl));
                return store(opt.get(), downloads);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return FetchResult.error("Interrupted");
            } catch (Exception e) {
                return FetchResult.error("Download failed: " + e.getMessage());
            }
        }
    }

    /** Opens the tender's department list in this session and returns the tender's detail link. */
    private String locateDetailPage(GepnicSession session, GepnicPortal portal, Tender tender, String gepnicId)
            throws IOException, InterruptedException {
        List<GepnicParser.OrganisationLink> orgs = GepnicParser.parseOrganisations(session.get(portal.organisationsUrl()));
        String topOrg = topOrganisation(tender.getAuthority());
        List<GepnicParser.OrganisationLink> ordered = new ArrayList<>();
        for (GepnicParser.OrganisationLink o : orgs) {
            if (topOrg != null && o.name().equalsIgnoreCase(topOrg)) ordered.add(0, o);
        }
        if (ordered.isEmpty()) return null;
        for (GepnicParser.OrganisationLink org : ordered) {
            for (GepnicParser.TenderRow row : GepnicParser.parseTenderList(session.get(org.url()))) {
                if (row.tenderId().equalsIgnoreCase(gepnicId)) return row.detailUrl();
            }
        }
        return null;
    }

    /**
     * Downloads every other document link on the unlocked tender page: the NIT link
     * ({@code docDownoad}) and the per-document work item links. Pages (non-files) are ignored.
     */
    private List<GepnicSession.RawResponse> downloadRemainingDocuments(String tenderId, GepnicSession session,
                                                                      Document page, String alreadyFetched)
            throws InterruptedException {
        List<GepnicSession.RawResponse> files = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        if (alreadyFetched != null) seen.add(GepnicSession.cleanUrl(alreadyFetched));
        for (String url : findDocumentLinks(page)) {
            if (!seen.add(GepnicSession.cleanUrl(url))) continue;
            try {
                GepnicSession.RawResponse r = session.getRaw(url);
                logResponse(tenderId, "extra document", r);
                if (!r.isHtml()) files.add(r);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                System.err.println("[StateDocs] " + tenderId + " extra document failed: " + e.getMessage());
            }
        }
        return files;
    }

    private static final java.util.regex.Pattern FILE_NAME =
            java.util.regex.Pattern.compile("(?i)\\.(pdf|xlsx?|docx?|zip|rar)\\b");

    /** NIT links, zip links and per-document links on a GePNIC tender page (popups excluded). */
    static List<String> findDocumentLinks(Document page) {
        List<String> out = new ArrayList<>();
        for (Element a : page.select("a[href]")) {
            String href = a.attr("href");
            if (a.attr("onclick").contains("openPopUp")) continue;
            String text = a.text().toLowerCase(Locale.ROOT);
            boolean nit = href.contains("component=docDownoad");
            boolean zip = a.selectFirst("img[src*=zipicon]") != null || text.contains("download as zip");
            Element row = a.closest("tr");
            boolean fileRow = href.contains("sp=") && row != null && FILE_NAME.matcher(row.text()).find()
                    && !text.contains("view more");
            if (nit || zip || fileRow) {
                String abs = a.absUrl("href");
                if (!abs.isEmpty() && !out.contains(abs)) out.add(abs);
            }
        }
        return out;
    }

    /** True when the CAPTCHA page carries the portal's "wrong CAPTCHA" style message. */
    static boolean mentionsCaptchaError(Document page) {
        String text = page.text().toLowerCase(Locale.ROOT);
        return text.contains("invalid captcha") || text.contains("incorrect captcha") || text.contains("wrong captcha")
                || text.contains("captcha mismatch") || text.contains("captcha does not match") || text.contains("enter valid captcha")
                || text.contains("enter correct captcha") || text.contains("captcha entered is incorrect");
    }

    /** One log line per portal response, so a failed document fetch can be diagnosed from the backend log. */
    private static void logResponse(String tenderId, String step, GepnicSession.RawResponse r) {
        String detail;
        if (r.isHtml()) {
            Document d = r.asDocument();
            String text = d.text();
            int i = text.toLowerCase(Locale.ROOT).indexOf("captcha");
            String around = i >= 0 ? text.substring(Math.max(0, i - 120), Math.min(text.length(), i + 120)) : "";
            detail = "html title=\"" + d.title() + "\" captchaForm=" + (parseCaptchaForm(d) != null)
                    + " zipLink=" + (findZipLink(d) != null) + " captchaText=\"" + around + "\"";
        } else {
            detail = "file bytes=" + r.body().length + " disposition=" + r.contentDisposition();
        }
        System.out.printf("[StateDocs] %s %s: HTTP %d %s %s | %s%n", tenderId, step, r.status(), r.contentType(),
                r.finalUrl(), detail);
    }

    static String findZipLink(Document detail) {
        for (Element a : detail.select("a[href]")) {
            boolean zipIcon = a.selectFirst("img[src*=zipicon]") != null;
            if (zipIcon || a.text().toLowerCase(Locale.ROOT).contains("download as zip")) return a.absUrl("href");
        }
        return null;
    }

    static CaptchaForm parseCaptchaForm(Document page) {
        Element form = page.selectFirst("form#frmCaptcha");
        if (form == null) return null;
        Element img = page.selectFirst("img#captchaImage");
        Element answer = form.selectFirst("input#captchaText, input[name=captchaText]");
        if (img == null || answer == null) return null;
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        for (Element input : form.select("input[name]")) {
            String type = input.attr("type").toLowerCase(Locale.ROOT);
            if (type.equals("button") || type.equals("image") || type.equals("reset")) continue;
            fields.add(new AbstractMap.SimpleEntry<>(input.attr("name"), input.val()));
        }
        String action = form.absUrl("action");
        return new CaptchaForm(action.isEmpty() ? page.location() : action, fields, answer.attr("name"), img.attr("src"), page.location());
    }

    private static String imageDataUri(GepnicSession session, String src) throws IOException, InterruptedException {
        if (src == null || src.isBlank()) return null;
        if (src.startsWith("data:")) return src;
        GepnicSession.RawResponse img = session.getRaw(src);
        String type = img.contentType().isBlank() ? "image/png" : img.contentType().split(";")[0];
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(img.body());
    }

    /** Saves the download (zip or single file) the way GeM bid documents are saved. */
    FetchResult store(Tender tender, GepnicSession.RawResponse resp) throws IOException {
        return store(tender, List.of(resp));
    }

    /** Saves each download (zips are kept and unpacked) the way GeM bid documents are saved. */
    FetchResult store(Tender tender, List<GepnicSession.RawResponse> responses) throws IOException {
        String id = tender.getId();
        Path dir = Paths.get(uploadDir, id);
        Files.createDirectories(dir);

        Map<String, byte[]> files = new LinkedHashMap<>();
        java.util.Set<String> digests = new java.util.HashSet<>();
        String base = safeName(firstNonBlank(tender.getEprocurementPortalId(), id));
        int zipNo = 0, otherNo = 0;
        for (GepnicSession.RawResponse resp : responses) {
            if (resp == null || resp.body() == null || resp.body().length == 0) continue;
            if (!digests.add(sha256(resp.body()))) continue; // same file served by two links
            String suggested = fileNameFromDisposition(resp.contentDisposition());
            if (isZipArchive(resp, suggested)) {
                zipNo++;
                String zipName = "Portal_Tender_Documents_" + base + (zipNo > 1 ? "_" + zipNo : "") + ".zip";
                files.put(zipName, resp.body());
                for (Map.Entry<String, byte[]> e : unzip(resp.body()).entrySet()) {
                    if (digests.add(sha256(e.getValue()))) putUnique(files, e.getKey(), e.getValue());
                }
            } else {
                otherNo++;
                String name = suggested != null ? safeName(suggested)
                        : "Portal_Tender_Document_" + base + "_" + otherNo + guessExtension(resp.contentType());
                putUnique(files, name, resp.body());
            }
        }

        String today = LocalDate.now().toString();
        List<Map<String, String>> entries = new ArrayList<>();
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String name = f.getKey();
            Files.write(dir.resolve(name), f.getValue());
            if (awsS3Enabled && objectStorageService != null && objectStorageService.isRemote()) {
                try {
                    objectStorageService.upload("tenders/" + id + "/" + name, new ByteArrayInputStream(f.getValue()),
                            contentTypeFor(name), f.getValue().length);
                } catch (Exception e) {
                    System.err.println("[StateDocs] S3 upload failed for " + id + "/" + name + " (local copy kept): " + e.getMessage());
                }
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", displayName(name));
            entry.put("filename", name);
            entry.put("local_path", "/documents/" + id + "/" + name);
            entry.put("created_date", today);
            entry.put("source", "portal");
            entries.add(entry);
        }

        tender.setDownloadedDocs(mergeDocs(tender.getDownloadedDocs(), entries));
        String main = pickMainDocument(entries);
        if (main != null && (tender.getDocumentUrl() == null || tender.getDocumentUrl().isBlank())) {
            tender.setDocumentUrl(main);
        }
        tenderRepository.save(tender);
        System.out.printf("[StateDocs] Stored %d file(s) for tender %s%n", entries.size(), id);
        return new FetchResult("DOWNLOADED", null, null,
                entries.size() + " document file(s) downloaded from the portal.", entries,
                tender.getDownloadedDocs(), tender.getDocumentUrl());
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String base = e.getName().replace('\\', '/');
                base = base.substring(base.lastIndexOf('/') + 1);
                if (base.isBlank()) continue;
                String name = safeName(base);
                if (out.containsKey(name)) name = out.size() + "_" + name;
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[64 * 1024];
                int n;
                while ((n = zin.read(chunk)) > 0) {
                    total += n;
                    if (total > MAX_TOTAL_BYTES) throw new IOException("Document package is larger than 300 MB");
                    buf.write(chunk, 0, n);
                }
                out.put(name, buf.toByteArray());
            }
        }
        return out;
    }

    String mergeDocs(String existingJson, List<Map<String, String>> entries) {
        try {
            ArrayNode arr = objectMapper.createArrayNode();
            if (existingJson != null && !existingJson.isBlank()) {
                JsonNode node = objectMapper.readTree(existingJson);
                if (node.isArray()) {
                    for (JsonNode n : node) {
                        String fn = n.path("filename").asText("");
                        boolean replaced = entries.stream().anyMatch(en -> en.get("filename").equals(fn));
                        if (!replaced) arr.add(n);
                    }
                }
            }
            for (Map<String, String> en : entries) {
                ObjectNode o = arr.addObject();
                en.forEach(o::put);
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            try {
                return objectMapper.writeValueAsString(entries);
            } catch (Exception ignored) {
                return "[]";
            }
        }
    }

    /** The PDF the tender page should treat as the tender document: full document first, then the notice. */
    static String pickMainDocument(List<Map<String, String>> entries) {
        String notice = null, anyPdf = null;
        for (Map<String, String> e : entries) {
            String fn = e.get("filename").toLowerCase(Locale.ROOT);
            if (!fn.endsWith(".pdf")) continue;
            if (fn.contains("tenderdocument") || fn.contains("tender_document")) return e.get("local_path");
            if (notice == null && fn.contains("tendernotice")) notice = e.get("local_path");
            if (anyPdf == null) anyPdf = e.get("local_path");
        }
        return notice != null ? notice : anyPdf;
    }

    static String displayName(String filename) {
        String fn = filename.toLowerCase(Locale.ROOT);
        if (fn.endsWith(".zip")) return "Portal Tender Documents (ZIP)";
        if (fn.contains("tendernotice")) return "Tender Notice / NIT (" + filename + ")";
        if (fn.contains("tenderdocument")) return "Tender Document (" + filename + ")";
        if (fn.startsWith("boq")) return "BOQ (" + filename + ")";
        return "Portal Document (" + filename + ")";
    }

    private Optional<GepnicPortal> portalFor(Tender t) {
        String source = t.getSource();
        if (source == null || !(source.startsWith("GePNIC-") || source.startsWith("STATE-"))) return Optional.empty();
        String code = source.substring(source.indexOf('-') + 1);
        return stateTenderSyncService.getPortals().stream()
                .filter(p -> p.code().equalsIgnoreCase(code) && p.sourceName().equalsIgnoreCase(source))
                .findFirst();
    }

    static String topOrganisation(String authority) {
        if (authority == null || authority.isBlank()) return null;
        String first = authority.split("\\s\\|\\s|\\|\\|")[0].trim();
        return first.isEmpty() ? null : first;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt));
    }

    private static void putUnique(Map<String, byte[]> files, String name, byte[] body) {
        String n = name;
        int i = 2;
        while (files.containsKey(n)) {
            int dot = name.lastIndexOf('.');
            n = dot > 0 ? name.substring(0, dot) + "_" + i + name.substring(dot) : name + "_" + i;
            i++;
        }
        files.put(n, body);
    }

    private static String sha256(byte[] b) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            return String.valueOf(java.util.Arrays.hashCode(b));
        }
    }

    /**
     * A download to unpack. Word/Excel files (.docx/.xlsx) are zip containers too, so the name decides
     * when there is one; only nameless downloads fall back to the zip signature.
     */
    static boolean isZipArchive(GepnicSession.RawResponse resp, String fileName) {
        if (!looksLikeZip(resp.body())) return false;
        if (fileName != null && !fileName.isBlank()) return fileName.toLowerCase(Locale.ROOT).trim().endsWith(".zip");
        return true;
    }

    private static boolean looksLikeZip(byte[] b) {
        return b != null && b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    private static String fileNameFromDisposition(String cd) {
        if (cd == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*?=\"?(?:UTF-8'')?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cd);
        return m.find() ? m.group(1).trim() : null;
    }

    static String safeName(String name) {
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        while (s.startsWith(".")) s = s.substring(1);
        return s.isEmpty() ? "document" : s;
    }

    private static String guessExtension(String contentType) {
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("pdf")) return ".pdf";
        if (ct.contains("zip")) return ".zip";
        if (ct.contains("excel") || ct.contains("spreadsheet")) return ".xls";
        return ".bin";
    }

    private static String contentTypeFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".doc")) return "application/msword";
        return "application/octet-stream";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
