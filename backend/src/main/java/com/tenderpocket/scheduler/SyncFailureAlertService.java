package com.tenderpocket.scheduler;

import jakarta.annotation.PreDestroy;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Emails the tech team when a scheduled job fails: a GeM import, a state / UT / central portal
 * import, the Tender247 email import, or any scheduled task that throws. One email per run lists
 * every source that failed, with the reason, times, counters and what happens next.
 *
 * <p>To limit noise: a source failing again with the same reason within
 * {@code sync.alert.repeat-suppress-hours} is not re-sent, failed intermediate retries are not
 * emailed unless {@code sync.alert.on-each-retry=true}, and the "gave up after retries" and
 * "recovered on retry" emails are always sent.
 */
@Service
public class SyncFailureAlertService {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm:ss a 'IST'", java.util.Locale.ENGLISH);

    public enum Event { FAILED, RETRY_FAILED, GAVE_UP, RECOVERED }

    /** One failed (or recovered) source within a run. */
    public static final class Incident {
        public final Event event;
        public final String source;
        public final String sourceName;
        public final String reason;
        /** Extra facts shown in the email, in order (times, counters, retry plan, portal URL...). */
        public final Map<String, String> details = new LinkedHashMap<>();

        public Incident(Event event, String source, String sourceName, String reason) {
            this.event = event;
            this.source = source;
            this.sourceName = sourceName;
            this.reason = reason == null || reason.isBlank() ? "(no error message)" : reason;
        }

        public Incident detail(String label, Object value) {
            if (value != null && !String.valueOf(value).isBlank()) details.put(label, String.valueOf(value));
            return this;
        }
    }

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${sync.alert.enabled:true}")
    private boolean enabled = true;

    /** Comma-separated tech team addresses; empty sends to the SMTP account itself. */
    @Value("${sync.alert.emails:}")
    private String recipients = "";

    @Value("${sync.alert.repeat-suppress-hours:6}")
    private long repeatSuppressHours = 6;

    @Value("${sync.alert.on-each-retry:false}")
    private boolean onEachRetry = false;

    /** Last time each source + reason was emailed, for suppression. */
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sync-failure-alert");
        t.setDaemon(true);
        return t;
    });

    @PreDestroy
    void shutdown() {
        sender.shutdown();
    }

    /**
     * Sends one email for the incidents of a run (after suppression), in the background so a slow
     * mail server never delays the scheduler.
     *
     * @param job       human-readable job name, e.g. "Daily state & central portal import"
     * @param startedAt when the run started (IST)
     */
    public void report(String job, LocalDateTime startedAt, List<Incident> incidents) {
        List<Incident> toSend = filter(incidents, Instant.now());
        if (toSend.isEmpty()) return;
        LocalDateTime finishedAt = LocalDateTime.now(IST);
        String subject = subject(job, toSend);
        String html = buildHtml(job, startedAt, finishedAt, toSend);
        sender.submit(() -> send(subject, html));
    }

    /** Alert for a scheduled task that threw instead of handling its own error. */
    public void reportUncaught(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = Arrays.stream(sw.toString().split("\\R")).limit(40).collect(Collectors.joining("\n"));
        Incident incident = new Incident(Event.FAILED, "SCHEDULER", "Scheduled task",
                t.getClass().getName() + ": " + t.getMessage())
                .detail("Thread", Thread.currentThread().getName())
                .detail("Stack trace (first 40 lines)", trace);
        report("Scheduled task error", null, List.of(incident));
    }

    /** Applies the retry and repeat rules. Package-private for tests. */
    List<Incident> filter(List<Incident> incidents, Instant now) {
        if (!enabled || incidents == null) return List.of();
        List<Incident> out = new ArrayList<>();
        for (Incident i : incidents) {
            if (i.event == Event.RETRY_FAILED && !onEachRetry) continue;
            if (i.event == Event.FAILED) {
                String key = i.source + "|" + i.reason;
                Instant previous = lastSent.get(key);
                if (previous != null && Duration.between(previous, now).toHours() < repeatSuppressHours) continue;
                lastSent.put(key, now);
            }
            out.add(i);
        }
        return out;
    }

    static String subject(String job, List<Incident> incidents) {
        String sources = incidents.stream().map(i -> i.sourceName).distinct().limit(6).collect(Collectors.joining(", "));
        long distinct = incidents.stream().map(i -> i.sourceName).distinct().count();
        if (distinct > 6) sources += " +" + (distinct - 6) + " more";
        boolean allRecovered = incidents.stream().allMatch(i -> i.event == Event.RECOVERED);
        boolean anyGaveUp = incidents.stream().anyMatch(i -> i.event == Event.GAVE_UP);
        String what = allRecovered ? "Recovered" : anyGaveUp ? "Still failing after retries" : "Failed";
        return "[TenderPocket] " + what + ": " + job + " - " + sources;
    }

    private static final String FONT = "font-family:'Segoe UI',Roboto,Helvetica,Arial,sans-serif;";

    /**
     * The alert email. Table-based with inline styles only, so it renders the same in Gmail,
     * Outlook and on phones.
     */
    String buildHtml(String job, LocalDateTime startedAt, LocalDateTime finishedAt, List<Incident> incidents) {
        long failed = incidents.stream().filter(i -> i.event != Event.RECOVERED).count();
        long recovered = incidents.size() - failed;
        boolean anyGaveUp = incidents.stream().anyMatch(i -> i.event == Event.GAVE_UP);
        String accent = failed == 0 ? "#059669" : anyGaveUp ? "#b91c1c" : "#d97706";
        String headline = failed == 0
                ? (recovered == 1 ? "1 source recovered" : recovered + " sources recovered")
                : anyGaveUp ? "Still failing after all retries"
                : failed == 1 ? "1 source failed" : failed + " sources failed";

        StringBuilder h = new StringBuilder();
        h.append("<div style=\"margin:0;padding:24px 12px;background:#f3f4f6;").append(FONT).append("\">");
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" align=\"center\" width=\"100%\" "
                + "style=\"max-width:680px;margin:0 auto;border-collapse:separate;background:#ffffff;"
                + "border:1px solid #e5e7eb;border-radius:12px;overflow:hidden\">");

        // Header band
        h.append("<tr><td style=\"background:").append(accent).append(";padding:22px 28px;color:#ffffff\">")
                .append("<div style=\"font-size:12px;letter-spacing:1.2px;text-transform:uppercase;opacity:.9\">TenderPocket &middot; Tender fetch alert</div>")
                .append("<div style=\"font-size:22px;font-weight:700;margin-top:6px;line-height:1.3\">").append(esc(headline)).append("</div>")
                .append("<div style=\"font-size:14px;margin-top:4px;opacity:.95\">").append(esc(job)).append("</div>")
                .append("</td></tr>");

        // Run summary
        h.append("<tr><td style=\"padding:20px 28px 4px\">");
        h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border-collapse:collapse\"><tr>");
        stat(h, String.valueOf(failed), "Failing", failed > 0 ? accent : "#6b7280");
        stat(h, String.valueOf(recovered), "Recovered", recovered > 0 ? "#059669" : "#6b7280");
        stat(h, startedAt != null ? CLOCK.format(startedAt) : "&ndash;", "Run started", "#111827");
        stat(h, CLOCK.format(finishedAt), "Reported", "#111827");
        h.append("</tr></table>");
        h.append("<div style=\"font-size:12px;color:#6b7280;margin-top:10px\">");
        if (startedAt != null) h.append("Run started ").append(esc(TIME.format(startedAt))).append(" &middot; ");
        h.append("Reported ").append(esc(TIME.format(finishedAt))).append(" &middot; Server ").append(esc(hostName())).append("</div>");
        h.append("</td></tr>");

        // One card per source
        for (Incident i : incidents) {
            String color = i.event == Event.RECOVERED ? "#059669" : i.event == Event.GAVE_UP ? "#b91c1c" : "#d97706";
            String tint = i.event == Event.RECOVERED ? "#ecfdf5" : i.event == Event.GAVE_UP ? "#fef2f2" : "#fffbeb";
            h.append("<tr><td style=\"padding:14px 28px 0\">");
            h.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" "
                    + "style=\"border-collapse:separate;border:1px solid #e5e7eb;border-left:5px solid ").append(color)
                    .append(";border-radius:8px\">");
            h.append("<tr><td style=\"padding:14px 16px 6px\">")
                    .append("<span style=\"font-size:16px;font-weight:700;color:#111827\">").append(esc(i.sourceName)).append("</span>")
                    .append(" <span style=\"font-size:12px;color:#6b7280\">").append(esc(i.source)).append("</span>")
                    .append("<span style=\"float:right;font-size:11px;font-weight:700;letter-spacing:.4px;text-transform:uppercase;color:")
                    .append(color).append(";background:").append(tint).append(";border:1px solid ").append(color)
                    .append(";border-radius:999px;padding:3px 10px\">").append(eventLabel(i.event)).append("</span>")
                    .append("</td></tr>");
            h.append("<tr><td style=\"padding:4px 16px 10px\">")
                    .append("<div style=\"font-size:11px;font-weight:700;letter-spacing:.6px;text-transform:uppercase;color:#6b7280;margin-bottom:4px\">")
                    .append(i.event == Event.RECOVERED ? "Earlier failure" : "Failure reason").append("</div>")
                    .append("<div style=\"font-size:14px;line-height:1.5;color:#111827;background:").append(tint)
                    .append(";border-radius:6px;padding:10px 12px;word-break:break-word\">").append(esc(i.reason)).append("</div>")
                    .append("</td></tr>");
            if (!i.details.isEmpty()) {
                h.append("<tr><td style=\"padding:0 16px 12px\"><table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\"border-collapse:collapse\">");
                int n = 0;
                for (Map.Entry<String, String> d : i.details.entrySet()) row(h, d.getKey(), d.getValue(), n++ % 2 == 1);
                h.append("</table></td></tr>");
            }
            h.append("</table></td></tr>");
        }

        // Footer
        h.append("<tr><td style=\"padding:20px 28px 24px\">")
                .append("<div style=\"border-top:1px solid #e5e7eb;padding-top:14px;font-size:12px;line-height:1.6;color:#6b7280\">")
                .append("Sent automatically by TenderPocket when a tender fetch fails or recovers. ")
                .append("The MIS Team sees the same sources on the <b>Fetch Status</b> page and can re-check one from there.<br>")
                .append("Live status: <span style=\"font-family:Consolas,monospace\">GET /api/tenders/fetch-status</span> &middot; ")
                .append("Logs: <span style=\"font-family:Consolas,monospace\">logs/tender-pocket-backend.log</span>")
                .append("</div></td></tr>");
        h.append("</table></div>");
        return h.toString();
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH);

    private static void stat(StringBuilder h, String value, String label, String color) {
        h.append("<td width=\"25%\" style=\"padding:0 6px 0 0;vertical-align:top\">")
                .append("<div style=\"border:1px solid #e5e7eb;border-radius:8px;padding:10px 12px\">")
                .append("<div style=\"font-size:20px;font-weight:700;color:").append(color).append("\">").append(value).append("</div>")
                .append("<div style=\"font-size:11px;letter-spacing:.4px;text-transform:uppercase;color:#6b7280;margin-top:2px\">")
                .append(label).append("</div></div></td>");
    }

    private static String eventLabel(Event e) {
        return switch (e) {
            case FAILED -> "failed";
            case RETRY_FAILED -> "retry failed";
            case GAVE_UP -> "still failing after all retries";
            case RECOVERED -> "recovered on retry";
        };
    }

    /** Labels whose value is a comma-separated list, shown as chips instead of one long line. */
    private static boolean isList(String label, String value) {
        return label.toLowerCase(java.util.Locale.ROOT).contains("keyword") && value.contains(",");
    }

    private static void row(StringBuilder h, String label, String value, boolean shaded) {
        String bg = shaded ? "background:#f9fafb;" : "";
        h.append("<tr><td style=\"").append(bg).append("font-size:12px;color:#6b7280;vertical-align:top;width:38%;padding:7px 10px;border-top:1px solid #f3f4f6\">")
                .append(esc(label)).append("</td><td style=\"").append(bg)
                .append("font-size:13px;color:#111827;vertical-align:top;padding:7px 10px;border-top:1px solid #f3f4f6;word-break:break-word\">");
        if (isList(label, value)) {
            for (String item : value.split(",")) {
                if (item.isBlank()) continue;
                h.append("<span style=\"display:inline-block;font-size:12px;color:#374151;background:#f3f4f6;border:1px solid #e5e7eb;"
                        + "border-radius:6px;padding:2px 8px;margin:0 4px 4px 0\">").append(esc(item.trim())).append("</span>");
            }
        } else if (value.startsWith("http://") || value.startsWith("https://")) {
            h.append("<a href=\"").append(esc(value)).append("\" style=\"color:#2563eb;text-decoration:none\">").append(esc(value)).append("</a>");
        } else if (value.contains("\n")) {
            h.append("<pre style=\"margin:0;font-family:Consolas,monospace;font-size:12px;line-height:1.5;white-space:pre-wrap;word-break:break-word\">")
                    .append(esc(value)).append("</pre>");
        } else {
            h.append(esc(value));
        }
        h.append("</td></tr>");
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ---- Is alert email working? -------------------------------------------------------------
    // A failure email that cannot be sent used to show up only in the server log, so nobody knew
    // the alerts were silent. The state below is shown on the Fetch Status page.

    private volatile LocalDateTime lastOkAt;
    private volatile LocalDateTime lastFailedAt;
    private volatile String lastError;
    private volatile LocalDateTime lastTestAt;

    private boolean hasSendingAccount() {
        return mailSender != null && fromEmail != null && !fromEmail.isBlank();
    }

    private void markOk() {
        lastOkAt = LocalDateTime.now(IST);
        lastError = null;
    }

    private void markFailed(String error) {
        lastFailedAt = LocalDateTime.now(IST);
        lastError = error;
        // Nothing reached the tech team, so the "already emailed this failure" memory must not
        // hold the next attempt back once sending works again.
        lastSent.clear();
    }

    /** "off", "not_set_up", "failing", "working" or "unknown" (nothing sent or checked yet). */
    public String state() {
        if (!enabled) return "off";
        if (!hasSendingAccount()) return "not_set_up";
        LocalDateTime ok = lastOkAt, failed = lastFailedAt;
        if (failed != null && (ok == null || failed.isAfter(ok))) return "failing";
        return ok != null ? "working" : "unknown";
    }

    /** For the Fetch Status page: plain state and times, no error text. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state());
        m.put("lastOkAt", lastOkAt == null ? null : lastOkAt.atZone(IST).toOffsetDateTime().toString());
        m.put("lastFailedAt", lastFailedAt == null ? null : lastFailedAt.atZone(IST).toOffsetDateTime().toString());
        return m;
    }

    /** Technical reason for the last failure (server log / tech team only). */
    public String lastError() {
        return lastError;
    }

    // Once, 30 s after startup: sign in to the mail server without sending anything, so a wrong
    // or revoked password is known right away rather than when the first failure email is lost.
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 30000)
    public void checkLoginAtStartup() {
        verifyLogin();
    }

    /** Signs in to the mail server without sending. Returns false when alerts cannot be sent. */
    boolean verifyLogin() {
        if (!enabled) return false;
        if (!hasSendingAccount()) {
            markFailed("No sending account (SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD are empty)");
            System.err.println("[Alert] Failure emails are NOT being sent: SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD are not set.");
            return false;
        }
        if (!(mailSender instanceof org.springframework.mail.javamail.JavaMailSenderImpl impl)) return true;
        try {
            impl.testConnection();
            markOk();
            System.out.println("[Alert] Email sign-in OK; failure emails go to " + recipientList().length + " address(es).");
            return true;
        } catch (Exception e) {
            markFailed(describeMailError(e));
            System.err.println("[Alert] Failure emails are NOT being sent: email sign-in failed (" + describeMailError(e)
                    + "). Check SPRING_MAIL_USERNAME and SPRING_MAIL_PASSWORD (Gmail app password).");
            return false;
        }
    }

    /**
     * Sends a test email to the tech team now. At most one a minute.
     *
     * @return true when the mail server accepted it
     */
    public boolean sendTest(String requestedBy) {
        LocalDateTime now = LocalDateTime.now(IST);
        LocalDateTime previous = lastTestAt;
        if (previous != null && previous.isAfter(now.minusMinutes(1))) return "working".equals(state());
        lastTestAt = now;
        String html = "<div style=\"font-family:Segoe UI,Arial,sans-serif;font-size:14px;color:#1f2937\">"
                + "<h2 style=\"margin:0 0 8px\">TenderPocket test alert</h2>"
                + "<p>This is a test. If you can read it, failure emails from TenderPocket are reaching this address.</p>"
                + "<p style=\"color:#6b7280;font-size:12px\">Sent " + esc(TIME.format(now)) + " from " + esc(hostName())
                + (requestedBy == null || requestedBy.isBlank() ? "" : ", requested by " + esc(requestedBy)) + ".</p></div>";
        return send("[TenderPocket] Test alert email", html);
    }

    private String[] recipientList() {
        return Arrays.stream((recipients == null || recipients.isBlank() ? fromEmail : recipients).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static boolean isSignInFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.mail.MailAuthenticationException
                    || t instanceof jakarta.mail.AuthenticationFailedException) return true;
        }
        return false;
    }

    private static String describeMailError(Throwable e) {
        return (isSignInFailure(e) ? "sign-in rejected: " : "") + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }

    private boolean send(String subject, String html) {
        if (!hasSendingAccount()) {
            markFailed("No sending account (SPRING_MAIL_USERNAME / SPRING_MAIL_PASSWORD are empty)");
            System.err.println("[Alert] No sending account configured; not sent: " + subject);
            return false;
        }
        String[] to = recipientList();
        if (to.length == 0) {
            markFailed("No recipients (SYNC_ALERT_EMAILS is empty)");
            System.err.println("[Alert] No recipients (set SYNC_ALERT_EMAILS); not sent: " + subject);
            return false;
        }
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(fromEmail.trim());
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(html, true);
                mailSender.send(message);
                markOk();
                System.out.println("[Alert] Sent: " + subject);
                return true;
            } catch (Exception e) {
                last = e;
                System.err.println("[Alert] Sending failed (attempt " + attempt + "/3): " + e.getMessage());
                // A rejected password will not start working on a second try, and repeated
                // failed sign-ins can get the mailbox temporarily locked.
                if (isSignInFailure(e)) break;
                try {
                    Thread.sleep(2000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        markFailed(last == null ? "unknown" : describeMailError(last));
        System.err.println("[Alert] NOT SENT: " + subject + ". The tech team did not get this email.");
        return false;
    }

    void configure(boolean enabled, long repeatSuppressHours, boolean onEachRetry) {
        this.enabled = enabled;
        this.repeatSuppressHours = repeatSuppressHours;
        this.onEachRetry = onEachRetry;
    }
}
