package com.tenderpocket.services.state;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Reads the public, CAPTCHA-free tender list of a non-NIC state eProcurement system.
 * NIC GePNIC portals are handled by {@link StateTenderSyncService} directly.
 */
public interface PortalListCrawler {

    /** Value of {@code type} in state-portals.json this crawler handles. */
    String type();

    /**
     * Returns active tenders, newest first where the portal allows it. Implementations stop paging
     * once tenders are older than {@code publishedAfter} when the portal exposes a published date.
     */
    List<ListedTender> list(GepnicPortal portal, GepnicSession session, LocalDateTime publishedAfter)
            throws IOException, InterruptedException;

    /**
     * One tender from a portal list.
     *
     * @param tenderId        portal's own tender id (numeric on these systems)
     * @param referenceNumber free-text notice / IFB number, may be null
     * @param organisation    department or buyer name
     * @param publishedAt     published date, null when the portal does not show it
     * @param estimatedValue  estimated contract value in rupees, null when not shown
     * @param category        portal category such as PRODUCTS / WORKS / SERVICES, may be null
     * @param docRef          portal-internal reference needed later to fetch documents, may be null
     * @param extra           additional label/value fields shaped like NIC detail labels (EMD Amount, ...), may be empty
     */
    record ListedTender(String tenderId, String title, String referenceNumber, String organisation,
                        LocalDateTime publishedAt, LocalDateTime closingAt, LocalDateTime openingAt,
                        Double estimatedValue, String category, String docRef, java.util.Map<String, String> extra) {

        public ListedTender(String tenderId, String title, String referenceNumber, String organisation,
                            LocalDateTime publishedAt, LocalDateTime closingAt, LocalDateTime openingAt,
                            Double estimatedValue, String category) {
            this(tenderId, title, referenceNumber, organisation, publishedAt, closingAt, openingAt, estimatedValue,
                    category, null, java.util.Map.of());
        }

        /** Adapter to the row shape the relevance rules and record builder use. */
        GepnicParser.TenderRow toRow() {
            return new GepnicParser.TenderRow(tenderId, title, referenceNumber, organisation == null ? "" : organisation,
                    publishedAt, closingAt, openingAt, null);
        }
    }
}
