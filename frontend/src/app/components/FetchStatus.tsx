"use client";

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { CircleAlert, CircleCheck, CircleDashed, Clock, ExternalLink, RadioTower, RefreshCw, Search, TriangleAlert } from 'lucide-react';

type SourceStatus = 'ok' | 'partial' | 'failed' | 'not_checked' | 'not_set_up';
type SourceKind = 'gem' | 'tender247' | 'central' | 'state' | 'ut';

interface FetchSource {
  key: string;
  name: string;
  kind: SourceKind;
  portalUrl: string;
  status: SourceStatus;
  checkingNow: boolean;
  lastCheckedAt: string | null;
  lastCompleteAt: string | null;
  lastReachedAt: string | null;
  newTenders: number | null;
  sectionsMissed: number;
  missedProducts: string[];
  nextAttemptAt: string | null;
}

interface FetchReport {
  summary: {
    total: number;
    ok: number;
    partial: number;
    failed: number;
    notChecked: number;
    /** Sources that can't be read until the tech team sets them up (Tender247 inbox). */
    notSetUp?: number;
    checkingNow: boolean;
    lastUpdatedAt: string | null;
  };
  sources: FetchSource[];
}

interface Props {
  fetchWithAuth: (url: string, options?: RequestInit) => Promise<Response>;
  /** Tells the sidebar how many portals need attention. */
  onAttentionCount?: (count: number) => void;
  showToast?: (message: string, type: 'success' | 'error') => void;
}

type Recheck = (s: FetchSource) => void;

function RecheckButton({ s, busy, onRecheck, compact }: { s: FetchSource; busy: boolean; onRecheck: Recheck; compact?: boolean }) {
  const disabled = busy || s.checkingNow;
  const label = s.checkingNow ? 'Checking…' : busy ? 'Starting…' : compact ? 'Check now' : 'Check again now';
  const title = s.kind === 'tender247'
    ? 'Read the Tender247 alert emails again now'
    : s.kind === 'gem' && s.missedProducts.length > 0
    ? `Search GeM again for the ${s.missedProducts.length} product(s) that were missed`
    : `Check ${s.name} again now`;
  return (
    <button type="button" className={compact ? 'btn-secondary' : 'btn-primary'} onClick={() => onRecheck(s)}
      disabled={disabled} title={title}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12,
        padding: compact ? '4px 10px' : '6px 12px', opacity: disabled ? 0.6 : 1,
        cursor: disabled ? 'not-allowed' : 'pointer', whiteSpace: 'nowrap',
      }}>
      <RefreshCw size={13} className={s.checkingNow || busy ? 'fetch-status-spin' : undefined} /> {label}
    </button>
  );
}

const KIND_LABEL: Record<SourceKind, string> = {
  gem: 'GeM',
  tender247: 'Email alerts',
  central: 'Central',
  state: 'State',
  ut: 'Union Territory',
};

const STATUS_STYLE: Record<SourceStatus, { label: string; color: string; bg: string }> = {
  ok: { label: 'Up to date', color: 'var(--accent-green)', bg: 'rgba(5, 150, 105, 0.1)' },
  partial: { label: 'Partly checked', color: 'var(--accent-yellow)', bg: 'rgba(217, 119, 6, 0.12)' },
  failed: { label: "Couldn't check", color: 'var(--accent-red)', bg: 'rgba(220, 38, 38, 0.1)' },
  not_checked: { label: 'Not checked yet', color: 'var(--text-muted)', bg: 'var(--bg-subtle)' },
  not_set_up: { label: 'Not connected', color: 'var(--accent-red)', bg: 'rgba(220, 38, 38, 0.1)' },
};

const IST = 'Asia/Kolkata';
const PRODUCTS_SHOWN = 12;

function dayKey(d: Date): string {
  return d.toLocaleDateString('en-CA', { timeZone: IST });
}

/** "Today, 6:42 AM", "Yesterday, 9:10 PM" or "14 Nov, 6:02 AM" (India time). */
function friendlyTime(iso: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  const time = d.toLocaleTimeString('en-IN', { timeZone: IST, hour: 'numeric', minute: '2-digit', hour12: true }).toUpperCase();
  const today = new Date();
  const yesterday = new Date(today.getTime() - 24 * 60 * 60 * 1000);
  if (dayKey(d) === dayKey(today)) return `Today, ${time}`;
  if (dayKey(d) === dayKey(yesterday)) return `Yesterday, ${time}`;
  const date = d.toLocaleDateString('en-IN', { timeZone: IST, day: 'numeric', month: 'short' });
  return `${date}, ${time}`;
}

function StatusChip({ status, checkingNow }: { status: SourceStatus; checkingNow?: boolean }) {
  if (checkingNow) {
    return (
      <span style={chipStyle('var(--accent-blue)', 'rgba(2, 132, 199, 0.1)')}>
        <RefreshCw size={12} className="fetch-status-spin" /> Checking now
      </span>
    );
  }
  const s = STATUS_STYLE[status];
  return <span style={chipStyle(s.color, s.bg)}>{s.label}</span>;
}

function chipStyle(color: string, bg: string): React.CSSProperties {
  return {
    display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 10px', borderRadius: 999,
    fontSize: 12, fontWeight: 600, color, background: bg, whiteSpace: 'nowrap',
  };
}

function nextTryText(s: FetchSource): string {
  return s.nextAttemptAt
    ? `We'll try again automatically around ${friendlyTime(s.nextAttemptAt)}.`
    : "We'll try again at the next regular update.";
}

function AttentionCard({ s, busy, onRecheck }: { s: FetchSource; busy: boolean; onRecheck: Recheck }) {
  const [showAll, setShowAll] = useState(false);
  const notSetUp = s.status === 'not_set_up';
  const failed = s.status === 'failed' || notSetUp;
  const products = showAll ? s.missedProducts : s.missedProducts.slice(0, PRODUCTS_SHOWN);

  let message: React.ReactNode;
  if (notSetUp) {
    message = <>Tender247 isn&apos;t connected yet, so no tenders from Tender247 alert emails are coming into TenderPocket. Please ask the tech team to connect it. Until then, check Tender247 directly.</>;
  } else if (failed && s.kind === 'tender247') {
    message = s.lastCompleteAt
      ? <>We couldn&apos;t open the Tender247 alert emails. Tenders sent after <b>{friendlyTime(s.lastCompleteAt)}</b> may be missing.</>
      : <>We haven&apos;t been able to open the Tender247 alert emails yet, so their tenders may be missing.</>;
  } else if (s.kind === 'tender247') {
    message = <>The last check ({friendlyTime(s.lastCheckedAt)}) couldn&apos;t read {s.sectionsMissed === 1 ? 'one Tender247 alert email' : `${s.sectionsMissed} Tender247 alert emails`}, so some of their tenders may be missing.</>;
  } else if (failed && s.kind === 'gem' && s.missedProducts.length === 0) {
    message = s.lastCompleteAt
      ? <>We couldn&apos;t check GeM. New GeM tenders published after <b>{friendlyTime(s.lastCompleteAt)}</b> may be missing.</>
      : <>We haven&apos;t been able to check GeM yet, so new GeM tenders may be missing.</>;
  } else if (failed) {
    message = s.lastCompleteAt
      ? <>We couldn&apos;t check this portal. Tenders published after <b>{friendlyTime(s.lastCompleteAt)}</b> may be missing.</>
      : <>We haven&apos;t been able to check this portal yet, so its tenders may be missing.</>;
  } else if (s.missedProducts.length > 0) {
    message = <>The last check ({friendlyTime(s.lastCheckedAt)}) didn&apos;t finish searching for these products, so some of their new tenders may be missing:</>;
  } else {
    message = <>The last check ({friendlyTime(s.lastCheckedAt)}) couldn&apos;t read {s.sectionsMissed === 1 ? 'one department' : `${s.sectionsMissed} departments`} on this portal, so a few of their new tenders may be missing.</>;
  }

  return (
    <div className="card" style={{
      padding: 16, borderLeft: `4px solid ${failed ? 'var(--accent-red)' : 'var(--accent-yellow)'}`,
      display: 'flex', flexDirection: 'column', gap: 10,
    }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' }}>
        <div>
          <div style={{ fontWeight: 700, fontSize: 15, color: 'var(--text-primary)' }}>{s.name}</div>
          <div style={{ fontSize: 12, color: 'var(--text-muted)' }}>{KIND_LABEL[s.kind]}</div>
        </div>
        <StatusChip status={s.status} checkingNow={s.checkingNow} />
      </div>
      <div style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.5 }}>{message}</div>
      {products.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {products.map(p => (
            <span key={p} style={{ fontSize: 12, padding: '2px 8px', borderRadius: 6, background: 'var(--bg-subtle)', border: '1px solid var(--border-color)', color: 'var(--text-secondary)' }}>{p}</span>
          ))}
          {s.missedProducts.length > PRODUCTS_SHOWN && (
            <button type="button" onClick={() => setShowAll(v => !v)}
              style={{ fontSize: 12, background: 'none', border: 'none', color: 'var(--primary)', cursor: 'pointer', padding: '2px 4px' }}>
              {showAll ? 'Show fewer' : `+${s.missedProducts.length - PRODUCTS_SHOWN} more`}
            </button>
          )}
        </div>
      )}
      {!notSetUp && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap', fontSize: 12, color: 'var(--text-muted)' }}>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><Clock size={13} /> {nextTryText(s)}</span>
        </div>
      )}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', gap: 8, flexWrap: 'wrap' }}>
        <a href={s.portalUrl} target="_blank" rel="noopener noreferrer" className="btn-secondary"
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, padding: '6px 12px', textDecoration: 'none' }}>
          {s.kind === 'tender247' ? 'Open Tender247' : 'Open the portal'} <ExternalLink size={13} />
        </a>
        {!notSetUp && <RecheckButton s={s} busy={busy} onRecheck={onRecheck} />}
      </div>
    </div>
  );
}

export default function FetchStatus({ fetchWithAuth, onAttentionCount, showToast }: Props) {
  const [report, setReport] = useState<FetchReport | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState('');
  /** Sources whose "Check again" request is being sent. */
  const [starting, setStarting] = useState<Set<string>>(new Set());
  const countRef = useRef(onAttentionCount);
  const toastRef = useRef(showToast);
  useEffect(() => { countRef.current = onAttentionCount; toastRef.current = showToast; });

  const load = useCallback(() => {
    return fetchWithAuth('/api/tenders/fetch-status')
      .then(async res => {
        const data = await res.json().catch(() => ({}));
        // Server messages are not meant for end users; keep the wording plain.
        if (res.status === 403) throw new Error('This page is only available to the MIS Team and Admin.');
        if (!res.ok || !data.success || !data.summary) throw new Error("We couldn't load the fetch status right now. Please try again in a minute.");
        setReport(data);
        setError(null);
        countRef.current?.((data.summary?.failed ?? 0) + (data.summary?.partial ?? 0) + (data.summary?.notSetUp ?? 0));
      })
      .catch(err => setError(err instanceof Error ? err.message : String(err)))
      .finally(() => setLoading(false));
  }, [fetchWithAuth]);

  useEffect(() => { load(); }, [load]);

  // Refresh every minute, or every 10 seconds while a portal is being checked.
  const checkingNow = Boolean(report?.summary.checkingNow);
  useEffect(() => {
    const id = setInterval(load, checkingNow ? 10_000 : 60_000);
    return () => clearInterval(id);
  }, [load, checkingNow]);

  const recheck = useCallback(async (s: FetchSource) => {
    setStarting(prev => new Set(prev).add(s.key));
    try {
      const res = await fetchWithAuth(`/api/tenders/fetch-status/${encodeURIComponent(s.key)}/recheck`, { method: 'POST' });
      const data = await res.json().catch(() => ({}));
      if (!res.ok || !data.success) {
        toastRef.current?.(res.status === 403 ? 'Only the MIS Team and Admin can start a check.'
          : "We couldn't start the check. Please try again in a minute.", 'error');
      } else {
        toastRef.current?.(data.message || 'Checking again now.', 'success');
      }
      await load();
    } catch {
      toastRef.current?.("We couldn't start the check. Please try again in a minute.", 'error');
    } finally {
      setStarting(prev => { const next = new Set(prev); next.delete(s.key); return next; });
    }
  }, [fetchWithAuth, load]);

  const attention = useMemo(
    () => (report?.sources ?? []).filter(s => s.status === 'failed' || s.status === 'partial' || s.status === 'not_set_up'),
    [report],
  );
  const visible = useMemo(() => {
    const q = search.trim().toLowerCase();
    return (report?.sources ?? []).filter(s => !q || s.name.toLowerCase().includes(q) || KIND_LABEL[s.kind].toLowerCase().includes(q));
  }, [report, search]);

  if (loading && !report) {
    return <div className="card" style={{ padding: 32, textAlign: 'center', color: 'var(--text-muted)' }}>Loading fetch status…</div>;
  }
  if (!report) {
    return (
      <div className="card empty-state" style={{ padding: 32 }}>
        <CircleAlert size={32} />
        <h3>Fetch status isn&apos;t available</h3>
        <p>{error}</p>
        <button className="btn-secondary" onClick={() => { setLoading(true); load(); }}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, padding: '8px 16px', marginTop: 8 }}>
          <RefreshCw size={14} /> Try again
        </button>
      </div>
    );
  }

  const { summary } = report;
  const needAttention = summary.failed + summary.partial + (summary.notSetUp ?? 0);
  const allGood = needAttention === 0 && summary.notChecked === 0;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <style>{`
        @keyframes fetch-status-spin { to { transform: rotate(360deg); } }
        .fetch-status-spin { animation: fetch-status-spin 1.2s linear infinite; }
        .fetch-status-table { width: 100%; border-collapse: collapse; font-size: 13px; }
        .fetch-status-table th { text-align: left; font-weight: 600; color: var(--text-muted); padding: 10px 12px; border-bottom: 1px solid var(--border-color); white-space: nowrap; }
        .fetch-status-table td { padding: 10px 12px; border-bottom: 1px solid var(--border-subtle); color: var(--text-secondary); vertical-align: middle; }
        .fetch-status-table tr:last-child td { border-bottom: none; }
        .fetch-status-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
        .fetch-status-tiles { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
        @media (max-width: 640px) {
          .fetch-status-tiles { grid-template-columns: 1fr; }
          .fetch-status-grid { grid-template-columns: 1fr; }
          .fetch-status-hide-sm { display: none; }
        }
      `}</style>

      {/* Overall message */}
      <div className="card" style={{
        padding: 18, display: 'flex', gap: 14, alignItems: 'flex-start',
        borderLeft: `4px solid ${allGood ? 'var(--accent-green)' : needAttention > 0 ? 'var(--accent-yellow)' : 'var(--text-muted)'}`,
      }}>
        {allGood ? <CircleCheck size={26} color="var(--accent-green)" /> : needAttention > 0
          ? <TriangleAlert size={26} color="var(--accent-yellow)" /> : <CircleDashed size={26} color="var(--text-muted)" />}
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontWeight: 700, fontSize: 16, color: 'var(--text-primary)' }}>
            {allGood ? 'All portals are up to date'
              : needAttention > 0 ? `${needAttention} ${needAttention === 1 ? 'portal needs' : 'portals need'} your attention`
              : 'Some portals have not been checked yet'}
          </div>
          <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 4, lineHeight: 1.5 }}>
            {allGood
              ? <>Every portal was checked and its new tenders are in TenderPocket. Latest update: {friendlyTime(summary.lastUpdatedAt)}.</>
              : needAttention > 0
                ? <>TenderPocket couldn&apos;t fully check {needAttention === 1 ? 'this portal' : 'these portals'}, so some new tenders from {needAttention === 1 ? 'it' : 'them'} may not be in the app yet. Until {needAttention === 1 ? "it's" : "they're"} back to normal, please look at {needAttention === 1 ? 'it' : 'them'} directly.</>
                : <>New portals are checked at the next regular update.</>}
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          {summary.checkingNow && <StatusChip status="ok" checkingNow />}
          <button className="btn-secondary" onClick={() => load()} title="Refresh this page"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, padding: '6px 12px' }}>
            <RefreshCw size={13} /> Refresh
          </button>
        </div>
      </div>

      {/* Counts */}
      <div className="fetch-status-tiles">
        {[
          { label: 'Up to date', value: summary.ok, color: 'var(--accent-green)' },
          { label: 'Need attention', value: needAttention, color: needAttention ? 'var(--accent-yellow)' : 'var(--text-muted)' },
          { label: 'Not checked yet', value: summary.notChecked, color: 'var(--text-muted)' },
        ].map(t => (
          <div key={t.label} className="card" style={{ padding: 16 }}>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', fontWeight: 600 }}>{t.label}</div>
            <div style={{ fontSize: 28, fontWeight: 700, color: t.color, fontVariantNumeric: 'tabular-nums' }}>
              {t.value}<span style={{ fontSize: 14, color: 'var(--text-muted)', fontWeight: 500 }}> / {summary.total}</span>
            </div>
          </div>
        ))}
      </div>

      {/* Portals that need attention */}
      {attention.length > 0 && (
        <section style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          <h3 style={{ margin: 0, fontSize: 15, color: 'var(--text-primary)' }}>You might be missing tenders from</h3>
          <div className="fetch-status-grid">
            {attention.map(s => <AttentionCard key={s.key} s={s} busy={starting.has(s.key)} onRecheck={recheck} />)}
          </div>
        </section>
      )}

      {/* Every portal */}
      <section className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, padding: '14px 16px', flexWrap: 'wrap' }}>
          <h3 style={{ margin: 0, fontSize: 15, color: 'var(--text-primary)', display: 'flex', alignItems: 'center', gap: 8 }}>
            <RadioTower size={16} /> All portals
          </h3>
          <div style={{ position: 'relative', width: 240, maxWidth: '100%' }}>
            <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
            <input value={search} onChange={e => setSearch(e.target.value)} placeholder="Find a state or portal"
              style={{ width: '100%', padding: '7px 10px 7px 30px', borderRadius: 8, border: '1px solid var(--border-color)', background: 'var(--bg-surface)', color: 'var(--text-primary)', fontSize: 13 }} />
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table className="fetch-status-table">
            <thead>
              <tr>
                <th>Portal</th>
                <th className="fetch-status-hide-sm">Type</th>
                <th>Last checked</th>
                <th className="fetch-status-hide-sm" style={{ textAlign: 'right' }}>New tenders found</th>
                <th>Status</th>
                <th aria-label="Actions"></th>
              </tr>
            </thead>
            <tbody>
              {visible.map(s => (
                <tr key={s.key}>
                  <td>
                    <a href={s.portalUrl} target="_blank" rel="noopener noreferrer"
                      style={{ color: 'var(--text-primary)', fontWeight: 600, textDecoration: 'none' }} title="Open this portal">
                      {s.name}
                    </a>
                  </td>
                  <td className="fetch-status-hide-sm">{KIND_LABEL[s.kind]}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>{friendlyTime(s.lastCheckedAt)}</td>
                  <td className="fetch-status-hide-sm" style={{ textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                    {s.newTenders ?? '—'}
                  </td>
                  <td><StatusChip status={s.status} checkingNow={s.checkingNow} /></td>
                  <td style={{ textAlign: 'right' }}>
                    {(s.status !== 'ok' || s.checkingNow) && s.status !== 'not_set_up' && (
                      <RecheckButton s={s} busy={starting.has(s.key)} onRecheck={recheck} compact />
                    )}
                  </td>
                </tr>
              ))}
              {visible.length === 0 && (
                <tr><td colSpan={6}style={{ textAlign: 'center', color: 'var(--text-muted)', padding: 24 }}>No portal matches &quot;{search}&quot;.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
