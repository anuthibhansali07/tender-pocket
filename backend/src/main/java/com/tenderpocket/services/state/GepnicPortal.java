package com.tenderpocket.services.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Locale;

/**
 * One state or central eProcurement portal, as configured in {@code state-portals.json}.
 *
 * @param code    short code used in tender ids and the {@code source} column (e.g. "MH")
 * @param name    display name
 * @param state   state or UT name stored on imported tenders; "Central" for the central portal
 * @param baseUrl NIC portals: the Tapestry app root (e.g. {@code https://mahatenders.gov.in/nicgep/app});
 *                other portals: the site root or public tender-list page
 * @param enabled whether the scheduled sync visits this portal
 * @param note    free-text reason a portal is disabled
 * @param type    "gepnic" (default, NIC GePNIC) or a non-NIC system: "apts" (AP/Telangana eProcurement),
 *                "nprocure" (Gujarat), "eproc2" (Bihar), "kppp" (Karnataka)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GepnicPortal(String code, String name, String state, String baseUrl, boolean enabled, String note, String type) {

    public GepnicPortal(String code, String name, String state, String baseUrl, boolean enabled, String note) {
        this(code, name, state, baseUrl, enabled, note, null);
    }

    /** Normalised portal type; missing means NIC GePNIC. */
    public String kind() {
        return type == null || type.isBlank() ? "gepnic" : type.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isGepnic() {
        return "gepnic".equals(kind());
    }

    public String organisationsUrl() {
        return baseUrl + "?page=FrontEndTendersByOrganisation&service=page";
    }

    /** Stable page a person can open to find the tender (portal links themselves expire). */
    public String entryUrl() {
        return isGepnic() ? organisationsUrl() : baseUrl;
    }

    /** Scheme and host, used to resolve the relative links portal pages contain. */
    public String origin() {
        int idx = baseUrl.indexOf('/', baseUrl.indexOf("://") + 3);
        return idx > 0 ? baseUrl.substring(0, idx) : baseUrl;
    }

    public String host() {
        String o = origin();
        return o.substring(o.indexOf("://") + 3);
    }

    /**
     * Value stored in the tenders.source column. NIC portals use "GePNIC-xx" (their documents can be
     * fetched with the CAPTCHA box); other state systems use "STATE-xx".
     */
    public String sourceName() {
        return (isGepnic() ? "GePNIC-" : "STATE-") + code;
    }

    /** Prefix for the tenders.id primary key. */
    public String idPrefix() {
        return (isGepnic() ? "gep-" : "st-") + code.toLowerCase(Locale.ROOT) + "-";
    }
}
