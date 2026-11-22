package com.tenderpocket.services.state;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the public pages every NIC GePNIC portal serves. All 30+ state portals and the central
 * portal run the same software, so these selectors hold across them.
 *
 * <p>Pages used (none needs a CAPTCHA):
 * <ul>
 *   <li>{@code FrontEndTendersByOrganisation}: departments with active-tender counts</li>
 *   <li>a department's tender list (one page, all active tenders, newest first)</li>
 *   <li>{@code FrontEndViewTender}: label/value detail table</li>
 * </ul>
 */
public final class GepnicParser {

    /** GePNIC tender id, e.g. 2026_DHS_1352679_1. */
    static final Pattern TENDER_ID = Pattern.compile("\\[(\\d{4}_[A-Za-z0-9]+_\\d+_\\d+)\\]\\s*$");

    private static final DateTimeFormatter PORTAL_DATE_TIME = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd-MMM-yyyy hh:mm a")
            .toFormatter(Locale.ENGLISH);

    private GepnicParser() {}

    public record OrganisationLink(String name, int activeCount, String url) {}

    public record TenderRow(String tenderId, String title, String referenceNumber, String organisationChain,
                            LocalDateTime publishedAt, LocalDateTime closingAt, LocalDateTime openingAt,
                            String detailUrl) {}

    /** Rows of the "Tenders by Organisation" table: S.No | Organisation Name | Tender Count (link). */
    public static List<OrganisationLink> parseOrganisations(Document doc) {
        List<OrganisationLink> result = new ArrayList<>();
        for (Element tr : doc.select("tr")) {
            Elements tds = tr.children().select("td");
            if (tds.size() != 3) continue;
            if (!tds.get(0).text().trim().matches("\\d+")) continue;
            Element link = tds.get(2).selectFirst("a[href]");
            if (link == null) continue;
            String countText = link.text().trim();
            if (!countText.matches("\\d+")) continue;
            result.add(new OrganisationLink(tds.get(1).text().trim(), Integer.parseInt(countText), link.absUrl("href")));
        }
        return result;
    }

    /**
     * Rows of a tender list table (same layout on the organisation view and the closing-date view):
     * S.No | e-Published Date | Closing Date | Opening Date | [Title] [Ref No][Tender ID] | Organisation Chain.
     */
    public static List<TenderRow> parseTenderList(Document doc) {
        List<TenderRow> result = new ArrayList<>();
        for (Element tr : doc.select("table.list_table tr")) {
            if (tr.hasClass("list_header")) continue;
            Elements tds = tr.children().select("td");
            if (tds.size() != 6) continue;
            if (!tds.get(0).text().trim().matches("\\d+\\.?")) continue;

            Element titleCell = tds.get(4);
            String cellText = clean(titleCell.text());
            Matcher idMatcher = TENDER_ID.matcher(cellText);
            if (!idMatcher.find()) continue;
            String tenderId = idMatcher.group(1);

            Element link = titleCell.selectFirst("a[href]");
            String linkText = link != null ? clean(link.text()) : "";
            String title = stripBrackets(linkText);

            // Text between the title link and "[tenderId]" is the free-text reference number.
            String rest = cellText;
            if (!linkText.isEmpty() && rest.startsWith(linkText)) rest = rest.substring(linkText.length());
            rest = rest.substring(0, rest.lastIndexOf("[" + tenderId + "]")).trim();
            String reference = stripBrackets(rest);

            result.add(new TenderRow(
                    tenderId,
                    title.isEmpty() ? reference : title,
                    reference,
                    clean(tds.get(5).text()),
                    parseDateTime(tds.get(1).text()),
                    parseDateTime(tds.get(2).text()),
                    parseDateTime(tds.get(3).text()),
                    link != null ? link.absUrl("href") : null));
        }
        return result;
    }

    /**
     * Label/value pairs from a tender detail page. Labels are normalised: currency symbols and a
     * trailing " in" are removed, so "EMD Amount in &#8377;" becomes "EMD Amount".
     */
    public static Map<String, String> parseDetail(Document doc) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Element caption : doc.select("td.td_caption")) {
            Element value = caption.nextElementSibling();
            if (value == null || !value.hasClass("td_field")) continue;
            String label = normaliseLabel(caption.text());
            if (label.isEmpty() || fields.containsKey(label)) continue;
            fields.put(label, clean(value.text()));
        }
        return fields;
    }

    /** True when the portal bounced the request to its "Unauthorised Access" page. */
    public static boolean isUnauthorisedPage(Document doc) {
        String location = doc.location() == null ? "" : doc.location();
        return location.contains("UnauthorisedAccessPage")
                || doc.text().contains("Server could not verify that you are authorised");
    }

    public static LocalDateTime parseDateTime(String text) {
        if (text == null) return null;
        String t = clean(text);
        if (t.isEmpty() || t.equalsIgnoreCase("NA")) return null;
        try {
            return LocalDateTime.parse(t, PORTAL_DATE_TIME);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parses Indian-grouped amounts like "4,94,000" or "45,00,000.50"; returns null for "NA" etc. */
    public static Double parseAmount(String text) {
        if (text == null) return null;
        String t = text.replaceAll("[^0-9.]", "");
        if (t.isEmpty() || t.equals(".")) return null;
        try {
            double v = Double.parseDouble(t);
            return v > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String normaliseLabel(String raw) {
        String s = clean(raw).replace("₹", "").replace("&#8377;", "").trim();
        if (s.endsWith(" in")) s = s.substring(0, s.length() - 3).trim();
        return s;
    }

    static String clean(String s) {
        if (s == null) return "";
        return s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String stripBrackets(String s) {
        String t = s.trim();
        if (t.startsWith("[")) t = t.substring(1);
        if (t.endsWith("]")) t = t.substring(0, t.length() - 1);
        return t.replace("][", " ").trim();
    }
}
