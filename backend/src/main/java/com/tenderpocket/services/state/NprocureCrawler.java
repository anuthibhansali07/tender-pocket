package com.tenderpocket.services.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gujarat's nProcure portal (tender.nprocure.com).
 *
 * <p>The public home page lists every active tender ("Tenders In Progress") through
 * {@code POST /beforeLoginTenderTableList}, a DataTables request no CAPTCHA guards. The page wraps
 * the request in AES: a random 5-character passphrase, random salt and IV, PBKDF2-SHA1 (1000
 * rounds, 128-bit key), AES-CBC, with the passphrase, salt and IV sent alongside the ciphertext.
 * This class reproduces that wrapping; the response is plain JSON.
 *
 * <p>The list has no published date, so every run reads the whole list (about 30 requests of 150
 * rows) and new tenders are told apart by Tender ID.
 */
public class NprocureCrawler implements PortalListCrawler {

    static final int PAGE_SIZE = 150;
    static final int MAX_PAGES = 60;

    private static final Pattern VALUE = Pattern.compile("Estimated Contract Value\\s*:\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)");
    private static final Pattern CLOSING = Pattern.compile("Last Date & Time For Submission\\s*:\\s*(\\d{2}-\\d{2}-\\d{4} \\d{2}:\\d{2}(?::\\d{2})?)");
    private static final DateTimeFormatter CLOSING_FORMAT = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm[:ss]");
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    @Override
    public String type() {
        return "nprocure";
    }

    @Override
    public List<ListedTender> list(GepnicPortal portal, GepnicSession session, LocalDateTime publishedAfter)
            throws IOException, InterruptedException {
        String origin = portal.origin();
        Document home = session.getRaw(origin + "/").asDocument();
        Element csrfMeta = home.selectFirst("meta[name=_csrf]");
        String csrf = csrfMeta == null ? "" : csrfMeta.attr("content");
        String tab = valueOf(home, "#tabFlagId", "Tenders In Progress");
        String tabList = valueOf(home, "#tabFlagIdList", "0");

        List<ListedTender> out = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            int start = page * PAGE_SIZE;
            String body = encryptedRequest(start, PAGE_SIZE, page + 1, csrf, tabList, tab);
            GepnicSession.RawResponse r = session.postJson(origin + "/beforeLoginTenderTableList", body,
                    Map.of("X-Requested-With", "XMLHttpRequest", "Referer", origin + "/",
                            "Accept", "application/json, text/javascript, */*; q=0.01"));
            JsonNode json = mapper.readTree(new String(r.body(), StandardCharsets.UTF_8));
            JsonNode rows = json.path("data");
            if (!rows.isArray() || rows.isEmpty()) break;
            for (JsonNode row : rows) {
                ListedTender t = parseRow(row);
                if (t != null) out.add(t);
            }
            int total = json.path("recordsFiltered").asInt(json.path("iTotalRecords").asInt(0));
            if (rows.size() < PAGE_SIZE || (total > 0 && start + PAGE_SIZE >= total)) break;
        }
        return out;
    }

    /**
     * Downloads every document of one tender. No CAPTCHA: the public "View NIT Document" page
     * ({@code POST /view-nit-document}) links each file at {@code /common/download?uid=...}.
     */
    public List<GepnicSession.RawResponse> downloadDocuments(GepnicPortal portal, GepnicSession session, String tenderId)
            throws IOException, InterruptedException {
        String origin = portal.origin();
        session.getRaw(origin + "/");
        Document page = session.postForm(origin + "/view-nit-document",
                List.of(Map.entry("_csrf", "token"), Map.entry("tenderid", tenderId)), origin + "/").asDocument();
        List<GepnicSession.RawResponse> files = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Element a : page.select("a[href*=/common/download]")) {
            String href = a.attr("href").replace(" ", "%20");
            String url = href.startsWith("http") ? href : origin + (href.startsWith("/") ? "" : "/") + href;
            if (!seen.add(url)) continue;
            GepnicSession.RawResponse r = session.getRaw(url);
            if (!r.isHtml()) files.add(r);
        }
        return files;
    }

    /** One DataTables row: "1" = IFB / notice no, "2" = HTML with department, tender id, title, value, closing. */
    static ListedTender parseRow(JsonNode row) {
        String ref = GepnicParser.clean(Jsoup.parse(row.path("1").asText("")).text());
        String html = row.path("2").asText("");
        if (html.isBlank()) return null;
        Document d = Jsoup.parse(html);
        Element idInput = d.selectFirst("input[name=tenderid]");
        if (idInput == null || idInput.val().isBlank()) return null;
        String tenderId = idInput.val().trim();

        Element firstSpan = d.selectFirst("span");
        String organisation = firstSpan == null ? "" : GepnicParser.clean(firstSpan.ownText());

        String title = "";
        for (Element a : d.select("a")) {
            String txt = GepnicParser.clean(a.text());
            int i = txt.indexOf("Name Of Work :");
            if (i >= 0) {
                title = txt.substring(i + "Name Of Work :".length()).trim();
                break;
            }
        }
        String text = GepnicParser.clean(d.text());
        Double value = null;
        Matcher vm = VALUE.matcher(text);
        if (vm.find()) value = GepnicParser.parseAmount(vm.group(1));
        LocalDateTime closing = null;
        Matcher cm = CLOSING.matcher(text);
        if (cm.find()) {
            try {
                closing = LocalDateTime.parse(cm.group(1), CLOSING_FORMAT);
            } catch (Exception ignored) {
                closing = null;
            }
        }
        return new ListedTender(tenderId, title.isEmpty() ? ref : title, ref.isEmpty() ? null : ref, organisation,
                null, closing, null, value, null);
    }

    /** Builds the AES-wrapped request body the nProcure home page sends. */
    String encryptedRequest(int start, int length, int echo, String csrf, String tabList, String tab) {
        ArrayNode ao = mapper.createArrayNode();
        put(ao, "sEcho", echo);
        put(ao, "iColumns", 3);
        put(ao, "sColumns", ",,");
        put(ao, "iDisplayStart", start);
        put(ao, "iDisplayLength", length);
        for (int i = 0; i < 3; i++) put(ao, "mDataProp_" + i, String.valueOf(i + 1));
        put(ao, "iSortCol_0", 0);
        put(ao, "sSortDir_0", "asc");
        put(ao, "iSortingCols", 1);
        put(ao, "bSortable_0", false);
        put(ao, "bSortable_1", true);
        put(ao, "bSortable_2", false);

        ObjectNode plain = mapper.createObjectNode();
        plain.set("reqData", ao);
        plain.put("_csrf", csrf);
        plain.put("idList", tabList);
        plain.put("id", tab);

        String passphrase = randomText(5);
        byte[] salt = new byte[16];
        byte[] iv = new byte[16];
        random.nextBytes(salt);
        random.nextBytes(iv);
        try {
            String cipher = encrypt(plain.toString(), passphrase, salt, iv);
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("jsonData", cipher);
            wrapper.put("iv", HexFormat.of().formatHex(iv));
            wrapper.put("salt", HexFormat.of().formatHex(salt));
            wrapper.put("key", Base64.getEncoder().encodeToString(passphrase.getBytes(StandardCharsets.UTF_8)));
            return wrapper.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES is not available in this JVM", e);
        }
    }

    /** Same as the site's AesUtil.encrypt: PBKDF2-SHA1(passphrase, salt, 1000, 128) + AES-CBC/PKCS7, Base64. */
    static String encrypt(String plainText, String passphrase, byte[] salt, byte[] iv) throws GeneralSecurityException {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] key = f.generateSecret(new PBEKeySpec(passphrase.toCharArray(), salt, 1000, 128)).getEncoded();
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return Base64.getEncoder().encodeToString(c.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
    }

    static String decrypt(String cipherText, String passphrase, byte[] salt, byte[] iv) throws GeneralSecurityException {
        SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        byte[] key = f.generateSecret(new PBEKeySpec(passphrase.toCharArray(), salt, 1000, 128)).getEncoded();
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return new String(c.doFinal(Base64.getDecoder().decode(cipherText)), StandardCharsets.UTF_8);
    }

    private String randomText(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private void put(ArrayNode ao, String name, Object value) {
        ObjectNode o = ao.addObject();
        o.put("name", name);
        if (value instanceof Integer i) o.put("value", i);
        else if (value instanceof Boolean b) o.put("value", b);
        else o.put("value", String.valueOf(value));
    }

    private static String valueOf(Document d, String selector, String fallback) {
        Element e = d.selectFirst(selector);
        return e == null || e.val().isBlank() ? fallback : e.val();
    }
}
