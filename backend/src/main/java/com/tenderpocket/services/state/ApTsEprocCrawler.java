package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Andhra Pradesh (tender.apeprocurement.gov.in) and Telangana (tender.telangana.gov.in)
 * eProcurement portals, which run the same software.
 *
 * <p>Public list, no CAPTCHA: open login.html for a session and CSRF token, post the home form to
 * TenderDetailsHome.html (what the "Current Tenders - More" link does), then page through
 * TenderDetailsHomeJson.html sorted by published date, newest first. AP returns that JSON
 * Base64-encoded; Telangana returns it plain.
 *
 * <p>Row layout: Telangana (10 columns) department, tender id, notice no, category, title, value,
 * published, bid start, closing, actions. AP (9 columns) has no separate published column; its
 * bid start date (column 6) is the publication date.
 */
public class ApTsEprocCrawler implements PortalListCrawler {

    static final int PAGE_SIZE = 25;
    static final int MAX_PAGES = 80;

    private static final DateTimeFormatter DATE_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("dd/MM/yyyy hh:mm a").toFormatter(Locale.ENGLISH);

    /** Empty filter fields the JSON endpoint expects to be present (it fails with 404/500 otherwise). */
    private static final String[] BLANK_PARAMS = {"nTenderID", "subDeptId", "ddlDistrict", "ddlMandal", "biddingType",
            "sProcurementType", "mECVValue1", "mECVValue2", "dtBidClosingselect", "dtBidClosing1", "dtBidClosing2",
            "dtTenderOpening1", "dtTenderOpening2", "hdnSearch4", "hdnSearch", "hdncorrigendumsDetails",
            "hdncorrigendumsDetails1", "hdnnoSearch", "hdncorrigendumsDetails2", "hdnPreviousPage", "hdnIndentID",
            "hdnTenderCategory", "hdnProcurementID", "hdnadvsearch", "hdnFromStatus", "typeOfWorkFromConsolidation",
            "popUPRequestParameter", "selectedCircleDivison", "selectedDepartmentID", "selectedProcurementType",
            "selectedTypeofWork", "aid"};

    private static final java.util.regex.Pattern DOC_LINK =
            java.util.regex.Pattern.compile("fnviewTenderDocuments\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() {
        return "apts";
    }

    /**
     * Downloads every document of one tender. No CAPTCHA: the list session opens
     * ViewTenderDocuments.html, whose links post to DownLoadFile.html and return the file.
     *
     * @param docRef "procurementId,indentId" stored at import
     */
    public List<GepnicSession.RawResponse> downloadDocuments(GepnicPortal portal, GepnicSession session, String docRef)
            throws IOException, InterruptedException {
        String[] ids = docRef == null ? new String[0] : docRef.split(",");
        if (ids.length != 2) throw new IOException("This tender has no document reference; re-import it from the portal");
        String origin = portal.origin();
        Document listPage = openSession(session, origin);

        // Same fields the list page's form carries, as fnviewTenderDocuments() sets them.
        Element listForm = listPage.selectFirst("form#tenderDetailsHome, form[name=tenderDetailsHome]");
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        java.util.Set<String> set = new java.util.HashSet<>(List.of("popUPRequestParameter", "hdnFromStatus",
                "hdnProcurementID", "hdnIndentID", "hdnPreviousPage"));
        if (listForm != null) {
            for (Element in : controls(listForm)) {
                String name = in.attr("name");
                if (set.contains(name)) continue;
                fields.add(new AbstractMap.SimpleEntry<>(name, in.val()));
            }
        }
        fields.add(new AbstractMap.SimpleEntry<>("popUPRequestParameter", "popUPRequestParameter"));
        fields.add(new AbstractMap.SimpleEntry<>("hdnFromStatus", "moretenders"));
        fields.add(new AbstractMap.SimpleEntry<>("hdnProcurementID", ids[0].trim()));
        fields.add(new AbstractMap.SimpleEntry<>("hdnIndentID", ids[1].trim()));
        fields.add(new AbstractMap.SimpleEntry<>("hdnPreviousPage", "TenderDetailsHome.html"));
        Document docsPage = session.postForm(origin + "/ViewTenderDocuments.html", fields, origin + "/TenderDetailsHome.html").asDocument();

        Element docForm = docsPage.selectFirst("form#viewTenderDocBean, form[name=viewTenderDocBean]");
        if (docForm == null) throw new IOException("The portal did not open the tender's documents page");
        List<GepnicSession.RawResponse> files = new ArrayList<>();
        // Telangana: downloadFun('id','name','path') -> DownLoadFile.html; AP: downloadFun('id') -> DownLoadFile1.html.
        java.util.regex.Pattern dl = java.util.regex.Pattern.compile(
                "downloadFun\\('([^']*)'(?:\\s*,\\s*'([^']*)'\\s*,\\s*'([^']*)')?\\)");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Element a : docsPage.select("a[onclick*=downloadFun]")) {
            java.util.regex.Matcher m = dl.matcher(a.attr("onclick"));
            if (!m.find() || !seen.add(m.group(1))) continue;
            List<Map.Entry<String, String>> f = new ArrayList<>();
            for (Element in : controls(docForm)) {
                String name = in.attr("name");
                String type = in.attr("type").toLowerCase(Locale.ROOT);
                if (type.equals("submit") || type.equals("button")) continue;
                String v = switch (name) {
                    case "hdndocIds" -> m.group(1);
                    case "hdndocName" -> m.group(2) != null ? m.group(2) : in.val();
                    case "hdnsPath" -> m.group(3) != null ? m.group(3) : in.val();
                    default -> in.val();
                };
                f.add(new AbstractMap.SimpleEntry<>(name, v));
            }
            String endpoint = m.group(2) != null ? "/DownLoadFile.html" : "/DownLoadFile1.html";
            GepnicSession.RawResponse file = session.postForm(origin + endpoint, f, origin + "/ViewTenderDocuments.html");
            if (!file.isHtml()) files.add(file);
        }
        return files;
    }

    private static List<Element> controls(Element form) {
        List<Element> els = form instanceof org.jsoup.nodes.FormElement fe ? fe.elements() : form.select("input");
        List<Element> out = new ArrayList<>();
        for (Element e : els) if ("input".equals(e.normalName()) && e.hasAttr("name")) out.add(e);
        return out;
    }

    @Override
    public List<ListedTender> list(GepnicPortal portal, GepnicSession session, LocalDateTime publishedAfter)
            throws IOException, InterruptedException {
        String origin = portal.origin();
        openSession(session, origin);

        List<ListedTender> out = new ArrayList<>();
        boolean reopened = false;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode json;
            try {
                json = fetchPage(session, origin, page * PAGE_SIZE);
            } catch (IOException e) {
                // The portal sits behind a load balancer and now and then drops the list session,
                // answering with an empty redirect instead of JSON. A fresh session works again.
                if (reopened) throw e;
                reopened = true;
                System.out.printf("[StateSync] %s list request failed (%s); opening a new session and retrying%n",
                        origin, e.getMessage());
                openSession(session, origin);
                json = fetchPage(session, origin, page * PAGE_SIZE);
            }
            JsonNode rows = json.path("aaData");
            if (!rows.isArray() || rows.isEmpty()) break;
            boolean reachedOld = false;
            for (JsonNode r : rows) {
                ListedTender t = parseRow(r);
                if (t == null) continue;
                if (publishedAfter != null && t.publishedAt() != null && t.publishedAt().isBefore(publishedAfter)) {
                    reachedOld = true;
                    continue;
                }
                out.add(t);
            }
            if (reachedOld || rows.size() < PAGE_SIZE) break;
        }
        return out;
    }

    /** login.html -> POST its form to TenderDetailsHome.html, as the site's "More" link does. */
    private Document openSession(GepnicSession session, String origin) throws IOException, InterruptedException {
        Document login = session.getRaw(origin + "/login.html").asDocument();
        Element form = login.selectFirst("form#loginForm");
        if (form == null) throw new IOException("Portal home page has changed: no loginForm");
        // Telangana's markup closes the form early in the parsed tree; FormElement.elements() keeps every
        // control the parser associated with the form, as a browser would submit it.
        List<Element> controls = form instanceof org.jsoup.nodes.FormElement fe ? fe.elements() : form.select("input");
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        for (Element in : controls) {
            if (!"input".equals(in.normalName()) || !in.hasAttr("name")) continue;
            String type = in.attr("type").toLowerCase(Locale.ROOT);
            if (type.equals("button") || type.equals("submit") || type.equals("image")
                    || type.equals("checkbox") || type.equals("radio")) continue;
            String name = in.attr("name");
            fields.add(new AbstractMap.SimpleEntry<>(name, "hdnType".equals(name) ? "current" : in.val()));
        }
        GepnicSession.RawResponse r = session.postForm(origin + "/TenderDetailsHome.html", fields, origin + "/login.html");
        System.out.printf("[StateSync] %s list session: HTTP %d %s, %d bytes, %d form fields%n",
                origin, r.status(), r.finalUrl(), r.body().length, fields.size());
        if (r.isHtml() && r.finalUrl().contains("SessionTimeOut")) {
            throw new IOException("Portal refused the list session (" + r.finalUrl() + ")");
        }
        return r.asDocument();
    }

    private JsonNode fetchPage(GepnicSession session, String origin, int start) throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder();
        for (String p : BLANK_PARAMS) add(q, p, "");
        add(q, "hdnPreviousPge", "TenderDetailsHome.html");
        add(q, "hdnEncryptNames", "hdnEncryptNames");
        add(q, "hdnEncryptValues", "hdnEncryptValues");
        add(q, "hdnType", "current");
        add(q, "nDepartmentID", "0");
        add(q, "sEcho", "1");
        add(q, "iColumns", "10");
        add(q, "sColumns", ",,,,,,,,,");
        add(q, "iDisplayStart", String.valueOf(start));
        add(q, "iDisplayLength", String.valueOf(PAGE_SIZE));
        for (int i = 0; i < 10; i++) {
            add(q, "mDataProp_" + i, String.valueOf(i));
            add(q, "bSortable_" + i, "true");
        }
        add(q, "iSortCol_0", "6");
        add(q, "sSortDir_0", "desc");
        add(q, "iSortingCols", "1");

        GepnicSession.RawResponse r = session.getRaw(origin + "/TenderDetailsHomeJson.html?" + q,
                Map.of("X-Requested-With", "XMLHttpRequest",
                        "Referer", origin + "/TenderDetailsHome.html",
                        "Accept", "application/json, text/javascript, */*; q=0.01"));
        try {
            return parseBody(new String(r.body(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException(e.getMessage() + " [HTTP " + r.status() + " " + r.contentType() + " " + r.body().length
                    + " bytes from " + r.finalUrl().replaceAll("\\?.*", "") + "]", e);
        }
    }

    /** Plain JSON (Telangana) or Base64-wrapped JSON (AP). */
    JsonNode parseBody(String body) throws IOException {
        String t = body == null ? "" : body.trim();
        if (!t.startsWith("{")) {
            try {
                t = new String(Base64.getMimeDecoder().decode(t), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException e) {
                throw new IOException("Unexpected list response: " + t.substring(0, Math.min(120, t.length())));
            }
        }
        if (!t.startsWith("{")) throw new IOException("Unexpected list response: " + t.substring(0, Math.min(120, t.length())));
        return mapper.readTree(t);
    }

    static ListedTender parseRow(JsonNode r) {
        if (r == null || !r.isArray() || r.size() < 8) return null;
        String id = text(r, 1);
        if (id.isEmpty()) return null;
        boolean telangana = r.size() >= 10;
        LocalDateTime published = parseDate(text(r, 6));
        LocalDateTime closing = parseDate(text(r, telangana ? 8 : 7));
        // The actions cell links "Download Tender Documents" as fnviewTenderDocuments(procurementId, indentId).
        String actions = r.get(r.size() - 1) == null ? "" : r.get(r.size() - 1).asText("");
        java.util.regex.Matcher dm = DOC_LINK.matcher(actions);
        String docRef = dm.find() ? dm.group(1) + "," + dm.group(2) : null;
        return new ListedTender(id, text(r, 4), emptyToNull(text(r, 2)), text(r, 0), published, closing, null,
                GepnicParser.parseAmount(text(r, 5)), emptyToNull(text(r, 3)), docRef, Map.of());
    }

    static LocalDateTime parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s.trim().replaceAll("\\s+", " "), DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode r, int i) {
        JsonNode n = r.get(i);
        return n == null || n.isNull() ? "" : GepnicParser.clean(org.jsoup.Jsoup.parse(n.asText()).text());
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static void add(StringBuilder q, String k, String v) {
        if (q.length() > 0) q.append('&');
        q.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
    }
}
