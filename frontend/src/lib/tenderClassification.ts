/**
 * Centralized Tender Classification & Ingestion Source Display Mapping
 *
 * Rules:
 * 1. Tender type comes only from the Organization Tender ID (ref_no): if it contains the word GEM
 *    the tender is 'GeM', otherwise (no ref_no, or a ref_no without GEM) it is 'Non GeM'.
 *    The backend stores the result in tender_type; classifyByRefNo is the same rule for live previews.
 * 2. User-Facing Classification Badges:
 *    - 'GeM' -> displays as 'GeM' with green styling (#10b981)
 *    - anything else -> displays as 'Non-GeM' with blue styling (#3b82f6)
 * 3. Ingestion Source Badges:
 *    - 'Tender247' / 'Tender 247' -> displays as 'Tender247 Portal'
 *    - 'GeM' -> displays as 'GeM Portal'
 *    - Remains separate and unchanged from classification.
 */

/** Same rule as the backend's TenderClassifier.classify: ref_no contains the word GEM, in any letter case. */
export function classifyByRefNo(refNo?: string | null): 'GeM' | 'Non GeM' {
  return refNo && /(^|[^a-z])gem([^a-z]|$)/i.test(refNo) ? 'GeM' : 'Non GeM';
}

export interface ClassificationDisplayBadge {
  label: 'GeM' | 'Non-GeM';
  color: string;
  bgColor: string;
  borderColor: string;
}

/**
 * Returns user-facing classification badge styling and label.
 * Maps 'GeM' -> 'GeM' (green); anything else -> 'Non-GeM' (blue).
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

  // Everything that is not GeM displays as 'Non-GeM' with blue styling
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
  AP: 'Andhra Pradesh', TS: 'Telangana', GJ: 'Gujarat', BR: 'Bihar', CG: 'Chhattisgarh', KA: 'Karnataka',
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
 * after the executive types the portal's CAPTCHA.
 */
export function isNicPortalSource(source?: string | null): boolean {
  return !!source && source.trim().startsWith('GePNIC-');
}

/**
 * Whether fetching this portal's documents needs the executive to type a CAPTCHA.
 * NIC portals and Manipur's new NIC portal do. Andhra Pradesh, Telangana, Gujarat, Bihar,
 * Chhattisgarh and Karnataka release documents without one.
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

const NON_NIC_PORTALS = new Set(['AP', 'TS', 'GJ', 'BR', 'MN', 'CG', 'KA']);

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
