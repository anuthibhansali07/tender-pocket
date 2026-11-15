package com.tenderpocket.services.state;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chhattisgarh's CHiPS eProcurement portal (eproc.cgstate.gov.in/CHEPS).
 *
 * <p>The open-tender list is behind an "Authentication Code" that the page itself prints as text and
 * asks the visitor to retype. CHiPS's technical team told the business that this code exists to
 * slow down request floods (DDoS), not to stop automated reading of public tenders. This crawler
 * therefore submits the printed code, once per session, and then reads the list and documents at a
 * polite pace. The portal ships disabled in state-portals.json; enable it only with CHiPS's written
 * confirmation on file.
 *
 * <p>Flow: sign-in page (session + search form) -> POST the search form -> code page -> POST the
 * code -> page 1 of results (100 rows) -> POST the results page's search form with pageNumber=n for
 * further pages. Details: POST viewRfqForm to rfq.action. Documents: POST viewOpenRfqAttachmentForm
 * to doc.action per attachment.
 */
public class ChipsCrawler implements PortalListCrawler {

    static final int MAX_PAGES = 40;

    private static final Pattern VIEW_RFQ = Pattern.compile("viewRfq\\((\\d+)\\s*,\\s*(\\d+)");
    private static final Pattern PAGE_MARKER = Pattern.compile("\\|\\s*(\\d+)\\s+of\\s+(\\d+)\\s*\\|");
    private static final Pattern VALUE = Pattern.compile("toFixSciNotation\\(([0-9.Ee+-]+)\\)");
    private static final Pattern START = Pattern.compile("START-\\s*([A-Za-z]{3} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M)");
    private static final Pattern DUE = Pattern.compile("DUE-\\s*([A-Za-z]{3} \\d{1,2}, \\d{4} \\d{1,2}:\\d{2}:\\d{2} [AP]M)");
    private static final Pattern ATTACHMENT = Pattern.compile(
            "viewOpenRfqAttachment\\('([^']*)','([^']*)','([^']*)','([^']*)','([^']*)',(\\d+),'([^']*)',(\\d+)\\)");
    private static final DateTimeFormatter DATE_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive().appendPattern("MMM d, yyyy h:mm:ss a").toFormatter(Locale.ENGLISH);

    @Override
    public String type() {
        return "chips";
    }

    private static String base(GepnicPortal portal) {
        return portal.origin() + "/CHEPS";
    }

    @Override
    public List<ListedTender> list(GepnicPortal portal, GepnicSession session, LocalDateTime publishedAfter)
            throws IOException, InterruptedException {
        Document page = openResults(portal, session);
        Map<String, ListedTender> out = new LinkedHashMap<>();
        for (ListedTender t : parseRows(page)) out.putIfAbsent(t.tenderId(), t);

        int[] marker = pageMarker(page);
        int total = marker == null ? 1 : marker[1];
        for (int n = 1; n < Math.min(total, MAX_PAGES); n++) {
            Document next = nextPage(portal, session, page, n);
            List<ListedTender> rows = parseRows(next);
            int before = out.size();
            for (ListedTender t : rows) out.putIfAbsent(t.tenderId(), t);
            if (rows.isEmpty() || out.size() == before) break;
            page = next;
        }
        List<ListedTender> list = new ArrayList<>(out.values());
        if (publishedAfter != null) {
            list.removeIf(t -> t.publishedAt() != null && t.publishedAt().isBefore(publishedAfter));
        }
        return list;
    }

    /** Sign-in page -> search form -> code page -> results page 1, all in one session. */
    Document openResults(GepnicPortal portal, GepnicSession session) throws IOException, InterruptedException {
        String base = base(portal);
        String listUrl = base + "/business/getOpenRfqListAction.do";
        Document home = session.getRaw(base + "/security/getSignInAction.do").asDocument();
        Element search = home.selectFirst("form[name=rfqSearchForm]");
        if (search == null) throw new IOException("Portal home page has changed: no tender search form");
        Document codePage = session.postForm(listUrl, formFields(search), base + "/security/getSignInAction.do").asDocument();

        Element codeForm = codePage.selectFirst("form[name=rfqSearchViewForm]");
        if (codeForm == null) {
            if (!parseRows(codePage).isEmpty()) return codePage; // no code asked this time
            throw new IOException("Portal did not show its authentication step");
        }
        String code = printedCode(codeForm);
        if (code == null) throw new IOException("The portal's authentication code was not found on the page");
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        for (Map.Entry<String, String> f : formFields(codeForm)) {
            fields.add("inputCaptchaValue".equals(f.getKey()) ? Map.entry(f.getKey(), code) : f);
        }
        Document results = session.postForm(listUrl, fields, listUrl).asDocument();
        if (results.text().contains("General Action Request Error")) {
            throw new IOException("Portal rejected the list request (General Action Request Error)");
        }
        // The results page keeps a (hidden) copy of the code form, so judge by the rows and the prompt.
        if (parseRows(results).isEmpty() && results.text().contains("Confirm Authentication Code")) {
            throw new IOException("Portal asked for the authentication code again");
        }
        return results;
    }

    /** The code the page prints next to "Authentication Code :". */
    static String printedCode(Element form) {
        for (Element b : form.select("b")) {
            if (!b.ownText().contains("Authentication Code") || b.ownText().contains("Confirm")) continue;
            Element i = b.selectFirst("i");
            if (i != null && !i.text().isBlank()) return i.text().trim();
        }
        return null;
    }

    private Document nextPage(GepnicPortal portal, GepnicSession session, Document results, int pageNumber)
            throws IOException, InterruptedException {
        Element form = results.selectFirst("form[name=rfqSearchForm]");
        if (form == null) return new Document("");
        List<Map.Entry<String, String>> fields = new ArrayList<>();
        boolean hasPage = false;
        for (Map.Entry<String, String> f : formFields(form)) {
            if ("pageNumber".equals(f.getKey())) {
                fields.add(Map.entry("pageNumber", String.valueOf(pageNumber)));
                hasPage = true;
            } else {
                fields.add(f);
            }
        }
        if (!hasPage) fields.add(Map.entry("pageNumber", String.valueOf(pageNumber)));
        String listUrl = base(portal) + "/business/getOpenRfqListAction.do";
        return session.postForm(listUrl, fields, listUrl).asDocument();
    }

    static int[] pageMarker(Document page) {
        Matcher m = PAGE_MARKER.matcher(page.text());
        return m.find() ? new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))} : null;
    }

    /** Result rows: S.No | Tender no | Title | START/DUE dates | Department | Value | ... */
    static List<ListedTender> parseRows(Document page) {
        List<ListedTender> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Element tr : page.select("tr")) {
            // Layout tables wrap the results table, so only rows whose own cells hold the link count.
            Elements tds = tr.children().select("td");
            if (tds.size() < 5) continue;
            Matcher vm = VIEW_RFQ.matcher(tds.html());
            if (!vm.find() || tr.select("tr tr").size() > 0) continue;
            if (!seen.add(vm.group(1))) continue;
            String id = vm.group(1);
            String part = vm.group(2);
            Element tooltip = tds.get(2).selectFirst(".tooltiptext");
            String title = GepnicParser.clean(tooltip != null ? tooltip.text() : tds.get(2).text());
            String dates = tds.get(3).text();
            String dept = GepnicParser.clean(tds.get(4).text());
            Double value = null;
            Matcher val = VALUE.matcher(tr.html());
            if (val.find()) {
                try {
                    double v = Double.parseDouble(val.group(1));
                    value = v > 0 ? v : null;
                } catch (NumberFormatException ignored) {
                    value = null;
                }
            }
            out.add(new ListedTender(id, title, null, dept, date(START, dates), date(DUE, dates), null, value, null,
                    id + "," + part, Map.of()));
        }
        return out;
    }

    /**
     * Downloads every attachment of one tender. Needs the list session (the code step), then the
     * tender's page (rfq.action) and one doc.action post per attachment. No further code is asked.
     *
     * @param docRef "rfqId,partNumber" stored at import
     */
    public List<GepnicSession.RawResponse> downloadDocuments(GepnicPortal portal, GepnicSession session, String docRef)
            throws IOException, InterruptedException {
        String[] ref = docRef == null ? new String[0] : docRef.split(",");
        if (ref.length != 2) throw new IOException("This tender has no document reference; re-import it from the portal");
        String base = base(portal);
        Document results = openResults(portal, session);
        Element viewForm = results.selectFirst("form[name=viewRfqForm]");
        if (viewForm == null) throw new IOException("The portal's tender page form was not found");
        List<Map.Entry<String, String>> vf = new ArrayList<>();
        for (Map.Entry<String, String> f : formFields(viewForm)) {
            switch (f.getKey()) {
                case "rfqId" -> vf.add(Map.entry("rfqId", ref[0].trim()));
                case "rfqPartNumber" -> vf.add(Map.entry("rfqPartNumber", ref[1].trim()));
                default -> vf.add(f);
            }
        }
        Document detail = session.postForm(base + "/business/rfq.action", vf, base + "/business/getOpenRfqListAction.do").asDocument();
        Element attachForm = detail.selectFirst("form[name=viewOpenRfqAttachmentForm]");
        if (attachForm == null) throw new IOException("The tender page has no attachments section");

        List<GepnicSession.RawResponse> files = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = ATTACHMENT.matcher(detail.html());
        while (m.find()) {
            String fileName = m.group(7);
            if (!seen.add(fileName)) continue;
            Map<String, String> values = Map.of("methodName", m.group(3), "popUp", m.group(2), "openRfqFlag", m.group(4),
                    "entityName", m.group(5), "entityId", m.group(6), "fileName", fileName, "entityOrgId", m.group(8));
            List<Map.Entry<String, String>> af = new ArrayList<>();
            for (Map.Entry<String, String> f : formFields(attachForm)) {
                af.add(values.containsKey(f.getKey()) ? Map.entry(f.getKey(), values.get(f.getKey())) : f);
            }
            for (String k : values.keySet()) {
                if (af.stream().noneMatch(e -> e.getKey().equals(k))) af.add(Map.entry(k, values.get(k)));
            }
            GepnicSession.RawResponse r = session.postForm(base + "/business/doc.action", af, base + "/business/rfq.action");
            if (r.isHtml() || r.body().length == 0) continue;
            // Stored names carry a long numeric prefix ("6974351843358433310_203859_NIT.docx"); keep the readable end.
            String readable = fileName.replaceFirst("^\\d{6,}_\\d+_", "");
            files.add(new GepnicSession.RawResponse(r.status(), r.contentType(),
                    "attachment; filename=\"" + readable.replace("\"", "") + "\"", r.body(), r.finalUrl()));
        }
        return files;
    }

    /** Inputs and selects a browser would submit for this form (first option when none is selected). */
    static List<Map.Entry<String, String>> formFields(Element form) {
        List<Element> controls = form instanceof org.jsoup.nodes.FormElement fe ? fe.elements() : form.select("input, select");
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Element c : controls) {
            String name = c.attr("name");
            if (name.isEmpty()) continue;
            if ("select".equals(c.normalName())) {
                Element opt = c.selectFirst("option[selected]");
                if (opt == null) opt = c.selectFirst("option");
                out.add(new AbstractMap.SimpleEntry<>(name, opt == null ? "" : opt.attr("value")));
                continue;
            }
            if (!"input".equals(c.normalName())) continue;
            String type = c.attr("type").toLowerCase(Locale.ROOT);
            if (type.equals("submit") || type.equals("button") || type.equals("image") || type.equals("reset")) continue;
            if ((type.equals("checkbox") || type.equals("radio")) && !c.hasAttr("checked")) continue;
            out.add(new AbstractMap.SimpleEntry<>(name, c.val()));
        }
        return out;
    }

    private static LocalDateTime date(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        if (!m.find()) return null;
        try {
            return LocalDateTime.parse(m.group(1), DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }
}
