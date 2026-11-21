package com.tenderpocket.services.state;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * One browsing session on one GePNIC portal.
 *
 * <p>GePNIC links ({@code $DirectLink ... session=T&sp=...}) only work inside the session that
 * produced them, so each portal run gets its own cookie jar and its requests are sent strictly one
 * after another. Not thread-safe by design: use one instance per portal per run.
 */
public class GepnicSession implements PageFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /** Shared TLS context: JVM trust store plus completion of chains that omit their intermediate. */
    private static final javax.net.ssl.SSLContext SSL = ChainCompletingTrustManager.sslContext();

    private final HttpClient client;
    private final long pauseMs;
    private final Duration timeout;
    private long lastRequestAt = 0;
    private int requestCount = 0;

    public GepnicSession(long pauseMs, Duration timeout) {
        this.pauseMs = pauseMs;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .sslContext(SSL)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        String proxyHost = System.getenv("HTTP_PROXY_HOST");
        String proxyPort = System.getenv("HTTP_PROXY_PORT");
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort != null && !proxyPort.isBlank()) {
            try {
                builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, Integer.parseInt(proxyPort))));
            } catch (NumberFormatException ignored) {
                // Invalid port: fall back to a direct connection.
            }
        }
        this.client = builder.build();
        this.timeout = timeout;
    }

    /**
     * Some portals double-encode ampersands in their links, so after HTML decoding a literal
     * "&amp;" remains. Sent as-is, the portal receives a parameter named "amp;page" instead of
     * "page" and loses track of the request (it then keeps asking for the CAPTCHA).
     */
    static String cleanUrl(String url) {
        return url == null ? null : url.replace("&amp;", "&");
    }

    @Override
    public Document get(String rawUrl) throws IOException, InterruptedException {
        String url = cleanUrl(rawUrl);
        IOException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            waitForTurn(attempt == 1 ? pauseMs : pauseMs * 3);
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .GET()
                        .build();
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                requestCount++;
                if (response.statusCode() >= 500) {
                    throw new IOException("HTTP " + response.statusCode() + " from " + url);
                }
                if (response.statusCode() >= 400) {
                    throw new PortalBlockedException("HTTP " + response.statusCode() + " from " + url);
                }
                String finalUrl = response.uri().toString();
                Document doc = Jsoup.parse(new String(response.body(), StandardCharsets.UTF_8), finalUrl);
                if (GepnicParser.isUnauthorisedPage(doc)) {
                    throw new PortalBlockedException("Portal returned its Unauthorised Access page for " + url);
                }
                return doc;
            } catch (PortalBlockedException e) {
                throw e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    @Override
    public int requestCount() {
        return requestCount;
    }

    /** A response that may be HTML or a file (zip/pdf/xls). */
    public record RawResponse(int status, String contentType, String contentDisposition, byte[] body, String finalUrl) {
        public boolean isHtml() {
            return contentType != null && contentType.toLowerCase().contains("text/html")
                    && (contentDisposition == null || !contentDisposition.toLowerCase().contains("attachment"));
        }

        public Document asDocument() {
            return Jsoup.parse(new String(body, charset()), finalUrl);
        }

        /** Charset the server declared (e.g. CHiPS sends windows-1252), UTF-8 otherwise. */
        java.nio.charset.Charset charset() {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)charset=([\\w-]+)").matcher(contentType == null ? "" : contentType);
            if (m.find()) {
                try {
                    String cs = m.group(1);
                    // ISO-8859-1 is routinely mislabelled windows-1252 (curly quotes, dashes); the latter is a superset.
                    return java.nio.charset.Charset.forName("ISO-8859-1".equalsIgnoreCase(cs) ? "windows-1252" : cs);
                } catch (Exception ignored) {
                    // unknown charset: fall back to UTF-8
                }
            }
            return StandardCharsets.UTF_8;
        }
    }

    /** GET that keeps the raw bytes, for links that may return a file instead of a page. */
    public RawResponse getRaw(String url) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder().uri(URI.create(cleanUrl(url))).GET());
    }

    /** POSTs an HTML form (application/x-www-form-urlencoded) within this session. */
    public RawResponse postForm(String rawUrl, java.util.List<java.util.Map.Entry<String, String>> fields, String rawReferer)
            throws IOException, InterruptedException {
        String url = cleanUrl(rawUrl);
        String referer = cleanUrl(rawReferer);
        StringBuilder body = new StringBuilder();
        for (java.util.Map.Entry<String, String> f : fields) {
            if (body.length() > 0) body.append('&');
            body.append(java.net.URLEncoder.encode(f.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(java.net.URLEncoder.encode(f.getValue() == null ? "" : f.getValue(), StandardCharsets.UTF_8));
        }
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("Content-Type", "application/x-www-form-urlencoded");
        h.put("Referer", referer != null && !referer.isBlank() ? referer : url);
        h.put("Origin", url.substring(0, url.indexOf('/', url.indexOf("://") + 3)));
        return send(HttpRequest.newBuilder().uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())), h);
    }

    /** GET with extra headers (e.g. X-Requested-With for AJAX endpoints). */
    public RawResponse getRaw(String url, java.util.Map<String, String> headers) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder().uri(URI.create(cleanUrl(url))).GET(), headers);
    }

    /** POSTs a JSON body with extra headers. */
    public RawResponse postJson(String url, String json, java.util.Map<String, String> headers)
            throws IOException, InterruptedException {
        java.util.Map<String, String> h = new java.util.LinkedHashMap<>();
        h.put("Content-Type", "application/json;charset=UTF-8");
        h.put("Accept", "application/json, text/plain, */*");
        if (headers != null) h.putAll(headers);
        return send(HttpRequest.newBuilder().uri(URI.create(cleanUrl(url)))
                .POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json, StandardCharsets.UTF_8)), h);
    }

    private RawResponse send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return send(builder, null);
    }

    private RawResponse send(HttpRequest.Builder builder, java.util.Map<String, String> headers)
            throws IOException, InterruptedException {
        waitForTurn(pauseMs);
        builder.timeout(timeout)
                .setHeader("User-Agent", USER_AGENT)
                .setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .setHeader("Accept-Language", "en-US,en;q=0.9");
        if (headers != null) headers.forEach(builder::setHeader);
        HttpRequest request = builder.build();
        HttpResponse<byte[]> response = null;
        for (int attempt = 1; ; attempt++) {
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                break;
            } catch (IOException e) {
                // Some portals (CHiPS) drop large downloads part-way through; the same request
                // usually succeeds on a second try. Other failures are reported as they are.
                String msg = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
                boolean cutShort = msg.contains("content-length") || msg.contains("eof") || msg.contains("connection reset")
                        || msg.contains("closed");
                if (!cutShort || attempt >= 3) throw e;
                Thread.sleep(2000L * attempt);
            }
        }
        requestCount++;
        if (response.statusCode() >= 400) {
            throw new IOException("HTTP " + response.statusCode() + " from " + request.uri());
        }
        return new RawResponse(response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(""),
                response.headers().firstValue("Content-Disposition").orElse(null),
                response.body(),
                response.uri().toString());
    }

    private void waitForTurn(long minGapMs) throws InterruptedException {
        long wait = lastRequestAt + minGapMs - System.currentTimeMillis();
        if (lastRequestAt > 0 && wait > 0) Thread.sleep(wait);
        lastRequestAt = System.currentTimeMillis();
    }

    /** The portal refused access (4xx or its Unauthorised page); retrying will not help. */
    public static class PortalBlockedException extends IOException {
        public PortalBlockedException(String message) {
            super(message);
        }
    }
}
