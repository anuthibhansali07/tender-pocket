package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bihar's eProc 2.0 portal (eproc2.bihar.gov.in).
 *
 * <p>The public "Open area Tender List" page carries a bearer token in a hidden
 * {@code #Authorization} field. With it, {@code POST /rest/openarea/getTenderList} returns every
 * active tender in one JSON array (dates as epoch milliseconds) and
 * {@code /rest/organization/loadAllDeptWithParent} maps department ids to names. No CAPTCHA.
 */
public class BiharEproc2Crawler implements PortalListCrawler {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String type() {
        return "eproc2";
    }

    @Override
    public List<ListedTender> list(GepnicPortal portal, GepnicSession session, LocalDateTime publishedAfter)
            throws IOException, InterruptedException {
        String listPage = portal.baseUrl();
        String context = portal.origin() + "/EPSV2Web";
        Document page = session.getRaw(listPage).asDocument();
        Element auth = page.selectFirst("#Authorization");
        if (auth == null || auth.val().isBlank()) throw new IOException("Portal list page has no access token");
        Map<String, String> headers = Map.of("Authorization", auth.val(), "Auth-Token", "X-Requested-With",
                "Referer", listPage);

        Map<Long, String> departments = new HashMap<>();
        Element org = page.selectFirst("script:containsData(orgId)");
        String orgId = "538";
        if (org != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("orgId\\s*=\\s*'(\\d+)'").matcher(org.data());
            if (m.find()) orgId = m.group(1);
        }
        try {
            GepnicSession.RawResponse d = session.postJson(context + "/rest/organization/loadAllDeptWithParent?orgId=" + orgId, "", headers);
            for (JsonNode n : mapper.readTree(new String(d.body(), StandardCharsets.UTF_8))) {
                departments.put(n.path("organizationId").asLong(), n.path("organizationName").asText(""));
            }
        } catch (IOException e) {
            // Department names are a nicety; the list is still usable without them.
        }

        GepnicSession.RawResponse r = session.postJson(context + "/rest/openarea/getTenderList", "{}", headers);
        JsonNode arr = mapper.readTree(new String(r.body(), StandardCharsets.UTF_8));
        List<ListedTender> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode n : arr) {
            ListedTender t = parse(n, departments);
            if (t == null) continue;
            if (publishedAfter != null && t.publishedAt() != null && t.publishedAt().isBefore(publishedAfter)) continue;
            out.add(t);
        }
        out.sort((a, b) -> {
            if (a.publishedAt() == null || b.publishedAt() == null) return 0;
            return b.publishedAt().compareTo(a.publishedAt());
        });
        return out;
    }

    /**
     * Downloads every document of one tender. No CAPTCHA: with the list page's public token,
     * {@code rest/tender/previewTenderByTenderId} lists the attachments (fields stored as
     * "path|...|name"), and {@code rest/openarea/downloadattachment.action} returns each file.
     */
    public List<GepnicSession.RawResponse> downloadDocuments(GepnicPortal portal, GepnicSession session, String tenderId)
            throws IOException, InterruptedException {
        String listPage = portal.baseUrl();
        String context = portal.origin() + "/EPSV2Web";
        Document page = session.getRaw(listPage).asDocument();
        Element auth = page.selectFirst("#Authorization");
        if (auth == null || auth.val().isBlank()) throw new IOException("Portal list page has no access token");
        Map<String, String> headers = Map.of("Authorization", auth.val(), "Auth-Token", "X-Requested-With", "Referer", listPage);
        String orgId = orgId(page);

        GepnicSession.RawResponse p = session.postJson(context + "/rest/tender/previewTenderByTenderId?tenderId=" + tenderId, "", headers);
        JsonNode preview = mapper.readTree(new String(p.body(), StandardCharsets.UTF_8));
        List<GepnicSession.RawResponse> files = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode t : preview.path("templates")) {
            if ("N".equalsIgnoreCase(t.path("isDownloadPossible").asText(""))) continue; // needs a bidder login and NDA
            String groupId = t.path("templategroupId").asText("");
            for (JsonNode f : t.path("templateFieldList")) {
                String value = f.path("value").asText("");
                if (f.path("dataTypeId").asInt() != 104 || !value.contains("|") || !seen.add(value)) continue;
                String name = value.substring(value.lastIndexOf('|') + 1).trim();
                List<Map.Entry<String, String>> form = List.of(
                        Map.entry("Authorization", auth.val()), Map.entry("tempGrpId", groupId),
                        Map.entry("tenderId", tenderId), Map.entry("fileName", value), Map.entry("orgId", orgId));
                try {
                    GepnicSession.RawResponse r = session.postForm(context + "/rest/openarea/downloadattachment.action", form, listPage);
                    if (!r.isHtml() && r.body().length > 0) {
                        // The server's own filename drops part of the name; use the attachment's real name.
                        files.add(new GepnicSession.RawResponse(r.status(), r.contentType(),
                                "attachment; filename=\"" + name.replace("\"", "") + "\"", r.body(), r.finalUrl()));
                    }
                } catch (IOException e) {
                    System.err.println("[StateDocs] Bihar attachment " + name + " failed: " + e.getMessage());
                }
            }
        }
        return files;
    }

    private static String orgId(Document page) {
        Element org = page.selectFirst("script:containsData(orgId)");
        if (org != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("orgId\\s*=\\s*'(\\d+)'").matcher(org.data());
            if (m.find()) return m.group(1);
        }
        return "538";
    }

    static ListedTender parse(JsonNode n, Map<Long, String> departments) {
        long id = n.path("currenttenderid").asLong(0);
        if (id <= 0) return null;
        String dept = departments.getOrDefault(n.path("currentdeptid").asLong(), "Government of Bihar");
        String ref = n.path("currentTenderrefno").isMissingNode() ? n.path("currenttenderrefno").asText(null) : n.path("currentTenderrefno").asText(null);
        String category = switch (n.path("currenttendercatid").asInt(0)) {
            case 101 -> "WORKS";
            case 102 -> "SERVICES";
            case 103 -> "GOODS";
            default -> null;
        };
        Double value = n.path("currentpacamt").isNumber() ? n.path("currentpacamt").asDouble() : null;
        return new ListedTender(String.valueOf(id), GepnicParser.clean(n.path("currentdescription").asText("")),
                ref == null || ref.isBlank() || "null".equals(ref) ? null : ref.trim(), dept,
                epoch(n.path("currentTenderPublishDate")), epoch(n.path("currentbidEndDate")),
                epoch(n.path("currentbidOpenDate")), value != null && value > 0 ? value : null, category);
    }

    private static LocalDateTime epoch(JsonNode n) {
        if (n == null || !n.isNumber()) return null;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(n.asLong()), IST);
    }
}
