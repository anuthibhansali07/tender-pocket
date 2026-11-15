/**
 * Centralized Tender Classification & Ingestion Source Display Mapping
 * 
 * Rules:
 * 1. User-Facing Classification Badges:
 *    - 'GeM' -> displays as 'GeM' with green styling (#10b981)
 *    - 'Non-GeM' or 'Non GeM' -> displays as 'Non-GeM' with blue styling (#3b82f6)
 *    - 'Unverified' -> displays as 'Non-GeM' with blue styling (#3b82f6)
 *    - null / undefined / empty -> displays as 'Non-GeM' with blue styling (#3b82f6)
 * 2. Ingestion Source Badges:
 *    - 'Tender247' / 'Tender 247' -> displays as 'Tender247 Portal'
 *    - 'GeM' -> displays as 'GeM Portal'
 *    - Remains separate and unchanged from classification.
 * 3. Stored internal values:
 *    - Backend classification rules, stored tender_type values, database records,
 *      email parsing, scraping, and enrichment logic retain the actual 'Unverified' value internally.
 */

export interface ClassificationDisplayBadge {
  label: 'GeM' | 'Non-GeM';
  color: string;
  bgColor: string;
  borderColor: string;
}

/**
 * Returns user-facing classification badge styling and label.
 * Maps 'GeM' -> 'GeM' (green).
 * Maps 'Non-GeM', 'Non GeM', and 'Unverified' -> 'Non-GeM' (blue).
 */
export function getClassificationDisplay(tenderType?: string | null): ClassificationDisplayBadge {
  const normalized = (tenderType || '').trim();

  if (/^gem(\s+bid)?$/i.test(normalized)) {
    return {
      label: 'GeM',
      color: '#10b981',
      bgColor: 'rgba(16, 185, 129, 0.1)',
      borderColor: 'rgba(16, 185, 129, 0.2)',
    };
  }

  // Both 'Non GeM'/'Non-GeM' and 'Unverified' display as 'Non-GeM' with blue styling
  return {
    label: 'Non-GeM',
    color: '#3b82f6',
    bgColor: 'rgba(59, 130, 246, 0.1)',
    borderColor: 'rgba(59, 130, 246, 0.2)',
  };
}

/**
 * Returns user-facing classification label ('GeM' or 'Non-GeM').
 */
export function getClassificationLabel(tenderType?: string | null): 'GeM' | 'Non-GeM' {
  return getClassificationDisplay(tenderType).label;
}

/**
 * Returns user-facing ingestion source badge label.
 * For Tender247 tenders, returns 'Tender247 Portal'.
 * For GeM tenders, returns 'GeM Portal'.
 */
export function getSourceBadgeLabel(source?: string | null): string {
  if (!source) return '';
  const trimmed = source.trim();
  if (trimmed === 'Tender247' || trimmed === 'Tender 247') {
    return 'Tender247 Portal';
  }
  if (trimmed === 'GeM') {
    return 'GeM Portal';
  }
  const statePortal = getStatePortal(trimmed);
  if (statePortal) {
    return statePortal.label;
  }
  return trimmed;
}

/**
 * NIC state & central eProcurement portals imported by the backend state sync.
 * Codes match backend/src/main/resources/state-portals.json; stored source is `GePNIC-<code>`.
 */
const STATE_PORTALS: Record<string, string> = {
  CENTRAL: 'Central', AN: 'Andaman & Nicobar', AR: 'Arunachal Pradesh', AS: 'Assam', CH: 'Chandigarh',
  DN: 'Dadra & Nagar Haveli', DD: 'Daman & Diu', DL: 'Delhi', GA: 'Goa', HR: 'Haryana',
  HP: 'Himachal Pradesh', JK: 'Jammu & Kashmir', JH: 'Jharkhand', KL: 'Kerala', LD: 'Lakshadweep',
  LA: 'Ladakh', MH: 'Maharashtra', MP: 'Madhya Pradesh', MN: 'Manipur', ML: 'Meghalaya',
  MZ: 'Mizoram', NL: 'Nagaland', OD: 'Odisha', PY: 'Puducherry', PB: 'Punjab', RJ: 'Rajasthan',
  SK: 'Sikkim', TN: 'Tamil Nadu', TR: 'Tripura', UK: 'Uttarakhand', UP: 'Uttar Pradesh', WB: 'West Bengal',
  // Non-NIC state systems (stored as STATE-<code>)
  AP: 'Andhra Pradesh', TS: 'Telangana', GJ: 'Gujarat', BR: 'Bihar', CG: 'Chhattisgarh',
};

/** Source badge colour for state/central eProcurement portals (amber, distinct from GeM green and Tender247 blue). */
export const STATE_PORTAL_COLOR = '#f59e0b';

/** True when the tender was imported from a state or central eProcurement portal (NIC `GePNIC-*` or other `STATE-*`). */
export function isStatePortalSource(source?: string | null): boolean {
  const s = (source || '').trim();
  return s.startsWith('GePNIC-') || s.startsWith('STATE-');
}

/**
 * True for NIC GePNIC portals, whose tender documents can be fetched inside TenderPocket
 * (the executive types the portal's CAPTCHA). Other state systems are list-only for now.
 */
export function isNicPortalSource(source?: string | null): boolean {
  return !!source && source.trim().startsWith('GePNIC-');
}

/**
 * Whether fetching this portal's documents needs the executive to type a CAPTCHA.
 * NIC portals and Manipur's new NIC portal do; AP, Telangana, Gujarat and Bihar release documents freely.
 */
export function portalDocsNeedCaptcha(source?: string | null): boolean {
  const s = (source || '').trim();
  return s.startsWith('GePNIC-') || s === 'STATE-MN';
}

/** `GePNIC-MH` / `STATE-TS` -> { code, state, label } */
export function getStatePortal(source?: string | null): { code: string; state: string; label: string } | null {
  if (!isStatePortalSource(source)) return null;
  const trimmed = source!.trim();
  const code = trimmed.substring(trimmed.indexOf('-') + 1).toUpperCase();
  const state = STATE_PORTALS[code] || code;
  return { code, state, label: code === 'CENTRAL' ? 'Central eProcurement Portal (CPPP)' : `${state} eProcurement Portal` };
}

const NON_NIC_PORTALS = new Set(['AP', 'TS', 'GJ', 'BR', 'MN', 'CG']);

/** Options for source filters (value is the stored source), sorted by state name. */
export const STATE_PORTAL_OPTIONS: { code: string; source: string; label: string }[] = Object.keys(STATE_PORTALS)
  .map((code) => {
    const source = `${NON_NIC_PORTALS.has(code) ? 'STATE' : 'GePNIC'}-${code}`;
    return { code, source, label: getStatePortal(source)!.label };
  })
  .sort((a, b) => a.label.localeCompare(b.label));

/**
 * Returns formatted Source Portal label for Quick Overview / Specifications.
 */
export function getSourcePortalLabel(source?: string | null): string {
  const badge = getSourceBadgeLabel(source);
  return badge || 'Not Specified';
}
