package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NIC's next-generation GePNIC portal (a Vue single-page app with a JSON API under
 * {@code /nggepnic_home_service}), used by Manipur since 2026.
 *
 * <p>Listing: its search and "by organisation / closing date / location" lists all require a
 * CAPTCHA. The home page's "Latest Tenders" box does not: {@code home/get-home-page-active-tenders}
 * returns the ten newest tenders, and {@code home/tender-details} (by work item id) returns the
 * GePNIC tender id, department, dates, fees and document list. Run frequently, that keeps up with a
 * small portal.
 *
 * <p>Documents need a CAPTCHA each time: {@code captcha/generate} (PNG, tied to the session), then
 * {@code download/downloadZipDocuments/validate} or {@code download/document/validate} with the
 * answer, then a GET of {@code download/document/ZipDocuments} or {@code download/document}.
 */
public class NgGepnicLatestCrawler implements PortalListCrawler {

    private static final DateTimeFormatter DATE_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd-MMM-yyyy hh:mm a").toFormatter(Locale.ENGLISH);

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() {
        return "nggepnic";
    }

    static String api(GepnicPortal portal) {
        return portal.origin() + "/nggepnic_home_service";
    }

    /** Headers the app sends; the API answers HTTP 500 without them. */
    static Map<String, String> headers(GepnicPortal portal) {
        return Map.of("Referer", portal.origin() + "/", "Origin", portal.origin());
    }

    @Override
    public List<ListedTender> list(GepnicPortal portal, GepnicSession session, LocalDateTime publishedAfter)
            throws IOException, InterruptedException {
        GepnicSession.RawResponse r = session.postJson(api(portal) + "/home/get-home-page-active-tenders", "{}", headers(portal));
        List<ListedTender> latest = parse(mapper.readTree(new String(r.body(), StandardCharsets.UTF_8)), portal.name());
        List<ListedTender> out = new ArrayList<>();
        for (ListedTender t : latest) {
            try {
                out.add(withDetails(t, details(portal, session, t.docRef())));
            } catch (IOException e) {
                out.add(t); // keep the list data; details are an improvement
            }
        }
        return out;
    }

    /** {@code home/tender-details} for one work item (the encrypted id the lists use). */
    JsonNode details(GepnicPortal portal, GepnicSession session, String workItemId) throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode().put("workItemId", workItemId);
        GepnicSession.RawResponse r = session.postJson(api(portal) + "/home/tender-details", body.toString(), headers(portal));
        JsonNode json = mapper.readTree(new String(r.body(), StandardCharsets.UTF_8));
        if (!json.has("tenderDetails")) throw new IOException("Tender details unavailable: " + json.path("error").asText(""));
        return json;
    }

    /** Home-page list rows: the encrypted work item id is both the row id and the document reference. */
    static List<ListedTender> parse(JsonNode json, String portalName) {
        List<ListedTender> out = new ArrayList<>();
        JsonNode list = json.path("latestTenders").path("latestTendersList");
        if (!list.isArray()) return out;
        for (JsonNode n : list) {
            String id = n.path("id").asText("").trim();
            String title = GepnicParser.clean(n.path("tenderTitle").asText(""));
            if (id.isEmpty() || title.isEmpty()) continue;
            String ref = GepnicParser.clean(n.path("referenceNo").asText(""));
            out.add(new ListedTender(id, title, ref.isEmpty() ? null : ref, "Government of " + portalName,
                    null, date(n.path("closingDate").asText(null)), date(n.path("BidOpeningDate").asText(null)), null, null,
                    id, Map.of()));
        }
        return out;
    }

    /** Merges {@code home/tender-details} into a list row: GePNIC tender id, department, dates, fees. */
    static ListedTender withDetails(ListedTender t, JsonNode json) {
        JsonNode td = json.path("tenderDetails");
        JsonNode oi = json.path("tenderOtherInfo");
        JsonNode dates = oi.path("tenderCriticalDates");
        JsonNode basic = oi.path("tenderBasicDetails");
        JsonNode work = oi.path("tenderWorkItemDetails");
        String gepnicId = td.path("tenderId").asText("");
        String org = GepnicParser.clean(td.path("orgChain").asText(""));
        Map<String, String> extra = new LinkedHashMap<>();
        put(extra, "EMD Amount", oi.path("emdfeeDetails").path("emdAmount").asText(null));
        put(extra, "Tender Fee", oi.path("tenderFeeDetails").path("totalTenderFee").asText(null));
        put(extra, "Tender Value", work.path("tenderValue").asText(null));
        put(extra, "Product Category", work.path("productCategory").asText(null));
        put(extra, "Form Of Contract", basic.path("formOfContract").asText(null));
        put(extra, "Location", work.path("location").asText(null));
        put(extra, "Pincode", work.path("pincode").asText(null));
        put(extra, "Pre Bid Meeting Date", dates.path("preBidMeetingDate").asText(null));
        LocalDateTime published = date(firstNonBlank(dates.path("publishDate").asText(null), basic.path("publishDate").asText(null)));
        LocalDateTime closing = date(dates.path("bidSubEndDate").asText(null));
        LocalDateTime opening = date(dates.path("bidOpeningDate").asText(null));
        String category = basic.path("tenderCategory").asText(null);
        return new ListedTender(gepnicId.isEmpty() ? t.tenderId() : gepnicId,
                firstNonBlank(GepnicParser.clean(work.path("workDescription").asText("")), t.title()),
                firstNonBlank(GepnicParser.clean(td.path("tenderRefNo").asText("")), t.referenceNumber()),
                org.isEmpty() ? t.organisation() : org,
                published != null ? published : t.publishedAt(),
                closing != null ? closing : t.closingAt(),
                opening != null ? opening : t.openingAt(),
                null, category == null ? null : category.toUpperCase(Locale.ROOT), t.docRef(), extra);
    }

    /** One downloadable unit on the tender page: a zip of work item documents, or a single NIT document. */
    record DocUnit(boolean zip, String docCode, String fileName, String packetType, String label) {}

    /** Work item documents are fetched as one zip; NIT and pre-bid documents individually. */
    static List<DocUnit> documentUnits(JsonNode json) {
        JsonNode oi = json.path("tenderOtherInfo");
        List<DocUnit> units = new ArrayList<>();
        java.util.Set<String> zipCodes = new java.util.HashSet<>();
        for (JsonNode d : oi.path("workItemDocuments")) {
            String code = d.path("docCode").asText("");
            if (!code.isEmpty() && zipCodes.add(code)) {
                units.add(new DocUnit(true, code, "", d.path("packetType").asText(""),
                        firstNonBlank(d.path("zipDocumentName").asText(null), "work item documents")));
            }
        }
        for (String key : new String[]{"nitDocuments", "preBidMeetingDocuments"}) {
            for (JsonNode d : oi.path(key)) {
                String code = d.path("docCode").asText("");
                String name = d.path("documentName").asText("");
                if (!code.isEmpty() && !name.isEmpty()) units.add(new DocUnit(false, code, cleanFileName(name), d.path("packetType").asText(""), name));
            }
        }
        return units;
    }

    /** "Tendernotice_1.pdf (signed)" -> "Tendernotice_1.pdf", as the site's own download code does. */
    static String cleanFileName(String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?\\.[A-Za-z0-9]+)\\s*(?:\\(|$)").matcher(name.trim());
        return m.find() ? m.group(1).trim() : name.trim();
    }

    /** A fresh CAPTCHA image for this session, as a data URI. */
    String captchaImage(GepnicPortal portal, GepnicSession session) throws IOException, InterruptedException {
        GepnicSession.RawResponse r = session.getRaw(api(portal) + "/captcha/generate?cd=" + System.currentTimeMillis(),
                Map.of("Referer", portal.origin() + "/", "Accept", "image/avif,image/webp,image/png,image/*,*/*;q=0.8"));
        String type = r.contentType().isBlank() ? "image/png" : r.contentType().split(";")[0];
        return "data:" + type + ";base64," + java.util.Base64.getEncoder().encodeToString(r.body());
    }

    /**
     * Posts the CAPTCHA answer for one document unit, then fetches the file in the same session.
     *
     * @return the file, or null when the portal rejected the CAPTCHA
     */
    GepnicSession.RawResponse download(GepnicPortal portal, GepnicSession session, String workItemId, DocUnit unit, String captcha)
            throws IOException, InterruptedException {
        ObjectNode body = mapper.createObjectNode()
                .put("downloadId", workItemId)
                .put("docCode", unit.docCode())
                .put("fileName", unit.zip() ? "" : unit.fileName())
                .put("packetType", unit.packetType() == null ? "" : unit.packetType())
                .put("captcha", captcha);
        String validate = api(portal) + (unit.zip() ? "/download/downloadZipDocuments/validate" : "/download/document/validate");
        GepnicSession.RawResponse v;
        try {
            v = session.postJson(validate, body.toString(), headers(portal));
        } catch (IOException e) {
            // HTTP 400 with a "captcha" error means a wrong answer.
            if (e.getMessage() != null && e.getMessage().contains("HTTP 400")) return null;
            throw e;
        }
        String text = new String(v.body(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (text.contains("captcha") && (text.contains("invalid") || text.contains("incorrect") || text.contains("error"))) return null;
        GepnicSession.RawResponse file = session.getRaw(api(portal) + (unit.zip() ? "/download/document/ZipDocuments" : "/download/document"),
                Map.of("Referer", portal.origin() + "/", "Accept", "*/*"));
        if (file.isHtml() || file.body().length == 0) throw new IOException("The portal accepted the CAPTCHA but sent no file");
        if (!unit.zip() && (file.contentDisposition() == null || !file.contentDisposition().contains("filename"))) {
            return new GepnicSession.RawResponse(file.status(), file.contentType(),
                    "attachment; filename=\"" + unit.fileName().replace("\"", "") + "\"", file.body(), file.finalUrl());
        }
        return file;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank() && !"null".equals(v) && !"NA".equalsIgnoreCase(v.trim())) m.put(k, v.trim());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank() && !"null".equals(v)) return v;
        return null;
    }

    static LocalDateTime date(String s) {
        if (s == null || s.isBlank() || "NA".equalsIgnoreCase(s.trim())) return null;
        try {
            return LocalDateTime.parse(s.trim(), DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }
}
