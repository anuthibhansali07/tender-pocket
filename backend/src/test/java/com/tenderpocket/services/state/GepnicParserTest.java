package com.tenderpocket.services.state;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Parser tests against pages saved from mahatenders.gov.in on 2026-11-12. */
class GepnicParserTest {

    static final String BASE = "https://mahatenders.gov.in/nicgep/app";

    static Document fixture(String name, String location) throws Exception {
        try (InputStream in = GepnicParserTest.class.getResourceAsStream("/fixtures/gepnic/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return Jsoup.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), location);
        }
    }

    @Test
    @DisplayName("Organisation page lists every department with its count and an absolute link")
    void parsesOrganisations() throws Exception {
        List<GepnicParser.OrganisationLink> orgs = GepnicParser.parseOrganisations(
                fixture("mahatenders-organisations.html", BASE + "?page=FrontEndTendersByOrganisation&service=page"));

        assertEquals(185, orgs.size());
        assertEquals("Yashwantrao Chavan Maharashtra Open University Nashik", orgs.get(orgs.size() - 1).name());
        GepnicParser.OrganisationLink dhs = orgs.stream()
                .filter(o -> o.name().equals("Directorate of Health Services, Mumbai")).findFirst().orElseThrow();
        assertEquals(17, dhs.activeCount());
        assertTrue(dhs.url().startsWith("https://mahatenders.gov.in/nicgep/app?component=%24DirectLink"), dhs.url());
        assertTrue(orgs.stream().mapToInt(GepnicParser.OrganisationLink::activeCount).sum() > 5000);
    }

    @Test
    @DisplayName("Department list yields title, reference, Tender ID, dates, org chain and detail link")
    void parsesTenderList() throws Exception {
        List<GepnicParser.TenderRow> rows = GepnicParser.parseTenderList(fixture("mahatenders-org-dhs.html", BASE));

        assertEquals(17, rows.size());
        GepnicParser.TenderRow first = rows.get(0);
        assertEquals("2026_DHS_1353554_1", first.tenderId());
        assertEquals("Purchase Of Medicines and Consumables for RRSH Amravati", first.title());
        assertEquals("E17/RRSHAmt/MedicineConsu/26", first.referenceNumber());
        assertEquals(LocalDateTime.of(2026, 11, 12, 10, 0), first.publishedAt());
        assertEquals(LocalDateTime.of(2026, 11, 30, 17, 0), first.closingAt());
        assertEquals(LocalDateTime.of(2026, 12, 1, 17, 5), first.openingAt());
        assertTrue(first.organisationChain().startsWith("Directorate of Health Services, Mumbai||"));
        assertTrue(first.detailUrl().contains("page=FrontEndViewTender"));

        GepnicParser.TenderRow bloodBank = rows.stream()
                .filter(r -> r.tenderId().equals("2026_DHS_1352679_1")).findFirst().orElseThrow();
        assertEquals("Supply of Blood Bank Equipment and instruments for Regional Refferal Services Hospital Amravati",
                bloodBank.title());
        assertEquals("E-5/GHAmt/DPDC/Blood Bank/Equip/2026-27", bloodBank.referenceNumber());
        assertTrue(rows.stream().allMatch(r -> r.tenderId().matches("\\d{4}_[A-Za-z0-9]+_\\d+_\\d+")));
    }

    @Test
    @DisplayName("Detail page labels are normalised and money/date fields parse")
    void parsesDetail() throws Exception {
        Map<String, String> d = GepnicParser.parseDetail(fixture("mahatenders-detail-2026_DHS_1352679_1.html", BASE));

        assertEquals("2026_DHS_1352679_1", d.get("Tender ID"));
        assertEquals("E-5/GHAmt/DPDC/Blood Bank/Equip/2026-27", d.get("Tender Reference Number"));
        assertEquals("Goods", d.get("Tender Category"));
        assertEquals("Medical Equipments/Waste", d.get("Product Category"));
        assertEquals("4,94,000", d.get("EMD Amount"));
        assertEquals("10,000", d.get("Tender Fee"));
        assertEquals("NA", d.get("Tender Value"));
        assertEquals("444602", d.get("Pincode"));
        assertEquals("District General Hospital Amravati", d.get("Location"));
        assertEquals(LocalDateTime.of(2026, 11, 21, 18, 0), GepnicParser.parseDateTime(d.get("Bid Submission End Date")));
        assertEquals(494000.0, GepnicParser.parseAmount(d.get("EMD Amount")));
        assertNull(GepnicParser.parseAmount(d.get("Tender Value")));
    }

    @Test
    void parsesIndianAmountsAndRejectsPlaceholders() {
        assertEquals(4500000.0, GepnicParser.parseAmount("45,00,000"));
        assertEquals(1234.5, GepnicParser.parseAmount("₹ 1,234.50"));
        assertNull(GepnicParser.parseAmount("NA"));
        assertNull(GepnicParser.parseAmount("0"));
        assertNull(GepnicParser.parseAmount(null));
        assertNull(GepnicParser.parseDateTime("NA"));
        assertEquals(LocalDateTime.of(2026, 11, 7, 11, 10), GepnicParser.parseDateTime(" 07-Nov-2026 11:10 AM "));
    }

    @Test
    void detectsUnauthorisedPage() {
        Document blocked = Jsoup.parse("<html><body>Unauthorised Access Server could not verify that you are authorised "
                + "to access the requested page</body></html>", "https://x.gov.in/nicgep/app?page=UnauthorisedAccessPage&service=page");
        assertTrue(GepnicParser.isUnauthorisedPage(blocked));
        assertFalse(GepnicParser.isUnauthorisedPage(Jsoup.parse("<html><body>ok</body></html>", BASE)));
    }
}
