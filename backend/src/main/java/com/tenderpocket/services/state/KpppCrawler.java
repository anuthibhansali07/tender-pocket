package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Karnataka Public Procurement Portal (kppp.karnataka.gov.in).
 *
 * <p>The live-search page draws a canvas CAPTCHA and checks the typed answer in the browser
 * ({@code ngx-captcha} compares the input with a code it just generated). That check is not sent
 * to the server. After it, the page posts the public search used here:
 * {@code POST /supplier-registration-service/v1/api/portal-service/.../search-eproc-tenders},
 * newest published open tenders first. Goods, works and services are separate paths. The body
 * must include {@code category}, {@code status} and {@code tenderType}; a thinner body makes the
 * server return HTTP 500.
 *
 * <p>Documents use the same public API, again with no CAPTCHA: {@code get-goods-tender-files}
 * (works and services have their own list paths) returns each file's name and uuid, and
 * {@code goods-tender-file/{uuid}/download-file} returns the bytes. The id in the path is the
 * tender {@code id} from search, not {@code nitId}.
 */
public class KpppCrawler implements PortalListCrawler {

    static final int PAGE_SIZE = 100;
    static final int MAX_PAGES = 30;

    private static final String API = "/supplier-registration-service/v1/api";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss");
    private static final List<String> CATEGORIES = List.of("GOODS", "WORKS", "SERVICES");

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() {
        return "kppp";
    }

    @Override
    public List<ListedTender> list(GepnicPortal portal, GepnicSession session, LocalDateTime publishedAfter)
            throws IOException, InterruptedException {
        String origin = portal.origin();
        Map<String, String> headers = headers(origin);
        List<ListedTender> out = new ArrayList<>();
        for (String category : CATEGORIES) {
            out.addAll(listCategory(origin, session, headers, category, publishedAfter));
        }
        return out;
    }

    private List<ListedTender> listCategory(String origin, GepnicSession session, Map<String, String> headers,
                                            String category, LocalDateTime publishedAfter)
            throws IOException, InterruptedException {
        List<ListedTender> out = new ArrayList<>();
        String urlBase = origin + API + searchPath(category)
                + "?size=" + PAGE_SIZE + "&order-by-tender-publish=true&page=";
        String body = searchBody(category);
        for (int page = 0; page < MAX_PAGES; page++) {
            GepnicSession.RawResponse r = session.postJson(urlBase + page, body, headers);
            JsonNode rows = mapper.readTree(new String(r.body(), StandardCharsets.UTF_8));
            if (!rows.isArray()) {
                throw new IOException("KPPP " + category + " search did not return a list");
            }
            if (rows.isEmpty()) break;
            boolean reachedOlder = false;
            for (JsonNode row : rows) {
                ListedTender t = parse(row);
                if (t == null) continue;
                if (publishedAfter != null && t.publishedAt() != null && t.publishedAt().isBefore(publishedAfter)) {
                    reachedOlder = true;
                    break;
                }
                out.add(t);
            }
            if (reachedOlder || rows.size() < PAGE_SIZE) break;
        }
        return out;
    }

    /**
     * Downloads every public document of one tender. No CAPTCHA.
     *
     * @param docRef {@code CATEGORY:tenderId} stored at import, or a bare tender id
     */
    public List<GepnicSession.RawResponse> downloadDocuments(GepnicPortal portal, GepnicSession session, String docRef)
            throws IOException, InterruptedException {
        String id = tenderId(docRef);
        if (id == null) throw new IOException("This tender has no KPPP id; re-import it from the portal");
        String origin = portal.origin();
        Map<String, String> headers = headers(origin);
        String preferred = categoryOf(docRef);
        List<String> order = new ArrayList<>();
        if (preferred != null) order.add(preferred);
        for (String c : CATEGORIES) if (!order.contains(c)) order.add(c);

        List<PortalFile> files = List.of();
        String category = preferred == null ? "GOODS" : preferred;
        IOException last = null;
        for (String c : order) {
            try {
                GepnicSession.RawResponse list = session.getRaw(origin + API + fileListPath(c, id), headers);
                JsonNode rows = mapper.readTree(new String(list.body(), StandardCharsets.UTF_8));
                if (!rows.isArray()) continue;
                List<PortalFile> parsed = parseFileList(rows);
                if (!parsed.isEmpty()) {
                    files = parsed;
                    category = c;
                    break;
                }
            } catch (IOException e) {
                last = e;
            }
        }
        if (files.isEmpty() && last != null) throw last;

        List<GepnicSession.RawResponse> out = new ArrayList<>();
        for (PortalFile file : files) {
            GepnicSession.RawResponse r = session.getRaw(origin + API + fileDownloadPath(category, id, file.uuid()), headers);
            if (r.isHtml() || r.body().length == 0) continue;
            if (r.contentDisposition() == null || !r.contentDisposition().toLowerCase(java.util.Locale.ROOT).contains("filename")) {
                String name = file.fileName() == null ? file.uuid() : file.fileName().replace("\"", "");
                r = new GepnicSession.RawResponse(r.status(), r.contentType(),
                        "attachment; filename=\"" + name + "\"", r.body(), r.finalUrl());
            }
            out.add(r);
        }
        return out;
    }

    static Map<String, String> headers(String origin) {
        return Map.of(
                "Origin", origin,
                "Referer", origin + "/",
                "Post", "CONTRACTOR-EPROC-CONTRACTOR");
    }

    /** {@code GOODS:64551} or a bare id. */
    static String tenderId(String docRef) {
        if (docRef == null || docRef.isBlank()) return null;
        int colon = docRef.indexOf(':');
        String id = (colon < 0 ? docRef : docRef.substring(colon + 1)).trim();
        return id.isEmpty() ? null : id;
    }

    static String categoryOf(String docRef) {
        if (docRef == null) return null;
        int colon = docRef.indexOf(':');
        if (colon <= 0) return null;
        String c = docRef.substring(0, colon).trim().toUpperCase(java.util.Locale.ROOT);
        return CATEGORIES.contains(c) ? c : null;
    }

    static String fileListPath(String category, String id) {
        String seg = switch (category) {
            case "WORKS" -> "get-works-tender-files";
            case "SERVICES" -> "get-services-tender-files";
            default -> "get-goods-tender-files";
        };
        return "/portal-service/" + id + "/" + seg;
    }

    static String fileDownloadPath(String category, String id, String uuid) {
        String seg = switch (category) {
            case "WORKS" -> "works-tender-file";
            case "SERVICES" -> "services-tender-file";
            default -> "goods-tender-file";
        };
        return "/portal-service/" + id + "/" + seg + "/" + uuid + "/download-file";
    }

    /** One file from a KPPP document list. */
    record PortalFile(String uuid, String fileName) {}

    static List<PortalFile> parseFileList(JsonNode rows) {
        List<PortalFile> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) return out;
        for (JsonNode n : rows) {
            String uuid = n.path("uuid").asText("").trim();
            if (uuid.isEmpty()) continue;
            String name = n.path("fileName").asText("").trim();
            if (name.isEmpty() || "null".equalsIgnoreCase(name)) name = n.path("documentType").asText("document");
            out.add(new PortalFile(uuid, name));
        }
        return out;
    }

    /** Path under the supplier-registration API. Goods has no category segment. */
    static String searchPath(String category) {
        return switch (category) {
            case "WORKS" -> "/portal-service/works/search-eproc-tenders";
            case "SERVICES" -> "/portal-service/services/search-eproc-tenders";
            default -> "/portal-service/search-eproc-tenders";
        };
    }

    /** Body the search accepts. Omitting tenderType makes the portal return HTTP 500. */
    String searchBody(String category) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("category", category);
        body.put("status", "PUBLISHED");
        body.put("tenderType", "OPEN");
        return mapper.writeValueAsString(body);
    }

    static ListedTender parse(JsonNode n) {
        String id = n.path("id").asText("");
        if (id.isBlank() || "0".equals(id) || "null".equals(id)) return null;
        String title = GepnicParser.clean(firstText(n, "title", "description"));
        if (title == null || title.isBlank()) return null;
        String ref = firstText(n, "tenderNumber", "fileName");
        String org = firstText(n, "deptName");
        String category = n.path("category").asText(null);
        Double value = n.path("ecv").isNumber() ? n.path("ecv").asDouble() : null;
        if (value != null && value <= 0) value = null;
        Map<String, String> extra = new LinkedHashMap<>();
        String location = firstText(n, "locationName");
        if (location != null) extra.put("Location", location);
        String docRef = (category == null || category.isBlank() ? "GOODS" : category) + ":" + id;
        return new ListedTender(id, title, ref, org == null ? "" : org,
                parseDate(n.path("publishedDate").asText(null)),
                parseDate(n.path("tenderClosureDate").asText(null)),
                null, value, category, docRef, extra);
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = n.path(f).asText("").trim();
            if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) return v;
        }
        return null;
    }

    private static LocalDateTime parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.trim(), DATE_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
