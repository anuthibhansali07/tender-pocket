'use client';

import React, { useEffect, useRef, useState } from 'react';
import { AlertCircle, CheckCircle2, Download, RefreshCw } from 'lucide-react';
import { STATE_PORTAL_COLOR } from '@/lib/tenderClassification';

/**
 * Fetches a state-portal tender's documents. The portal shows a CAPTCHA before it releases the
 * documents, so an executive reads the portal's own image and types the characters; the backend
 * then downloads the files and stores them like GeM bid documents.
 *
 * Phases: idle -> opening (backend opens the tender on the portal) -> captcha (waiting for the
 * executive) -> submitting (portal checks the answer and sends the zip) -> done / back to captcha.
 */

type Phase = 'idle' | 'opening' | 'captcha' | 'submitting';

export interface PortalDocsResult {
  message?: string;
  downloadedDocs?: string;
  documentUrl?: string;
  files?: { name: string; filename: string; local_path: string }[];
}

/** Response shape of the backend portal-documents endpoints. */
interface BackendResponse extends PortalDocsResult {
  status?: 'CAPTCHA_REQUIRED' | 'DOWNLOADED' | 'ERROR';
  token?: string;
  captchaImage?: string;
  error?: string;
}

const errorMessage = (e: unknown, fallback: string) => (e instanceof Error && e.message ? e.message : fallback);

interface Props {
  tenderId: string;
  portalLabel: string;
  hasPortalDocs: boolean;
  /** False for portals that release documents without a CAPTCHA (one-click download). */
  needsCaptcha?: boolean;
  fetchWithAuth: (url: string, options?: RequestInit) => Promise<Response>;
  onDownloaded: (result: PortalDocsResult) => Promise<void> | void;
  showToast: (message: string, type: 'success' | 'error') => void;
}

/** The backend keeps the portal session for 10 minutes; warn a little before that. */
const CAPTCHA_LIFETIME_MS = 10 * 60 * 1000;
const OPEN_TIMEOUT_MS = 3 * 60 * 1000;
const SUBMIT_TIMEOUT_MS = 6 * 60 * 1000;
const PORTAL_CAPTCHA_LENGTH = 6;

const OPENING_STEPS = [
  { after: 0, text: 'Connecting to the portal…' },
  { after: 4, text: 'Finding the department’s tender list…' },
  { after: 12, text: 'Opening the tender page…' },
  { after: 25, text: 'Requesting the document download… large departments can take up to a minute.' },
  { after: 60, text: 'The portal is responding slowly. Still trying…' },
];

const SUBMITTING_STEPS = [
  { after: 0, text: 'Sending your answer to the portal…' },
  { after: 4, text: 'Portal accepted the request, downloading the document package…' },
  { after: 20, text: 'Downloading… tender documents can be 20–50 MB.' },
  { after: 60, text: 'Still downloading. Large packages on slow portals can take a few minutes.' },
];

function stepText(steps: { after: number; text: string }[], seconds: number) {
  let text = steps[0].text;
  for (const s of steps) if (seconds >= s.after) text = s.text;
  return text;
}

function formatClock(ms: number) {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export default function PortalDocumentFetcher({ tenderId, portalLabel, hasPortalDocs, needsCaptcha = true, fetchWithAuth, onDownloaded, showToast }: Props) {
  const [phase, setPhase] = useState<Phase>('idle');
  const [token, setToken] = useState<string | null>(null);
  const [image, setImage] = useState<string | null>(null);
  const [answer, setAnswer] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [phaseStartedAt, setPhaseStartedAt] = useState(0);
  const [expiresAt, setExpiresAt] = useState<number | null>(null);
  const [attempts, setAttempts] = useState(0);
  const [now, setNow] = useState(() => Date.now());
  const inputRef = useRef<HTMLInputElement>(null);
  const abortRef = useRef<AbortController | null>(null);

  const busy = phase === 'opening' || phase === 'submitting';
  const elapsed = Math.floor((now - phaseStartedAt) / 1000);
  const remainingMs = expiresAt ? expiresAt - now : 0;
  const expired = phase === 'captcha' && expiresAt !== null && remainingMs <= 0;
  const expiringSoon = phase === 'captcha' && !expired && remainingMs < 2 * 60 * 1000;
  const trimmed = answer.trim();
  const lengthWarning = trimmed.length > 0 && trimmed.length !== PORTAL_CAPTCHA_LENGTH
    ? `The portal's CAPTCHA has ${PORTAL_CAPTCHA_LENGTH} characters; you typed ${trimmed.length}.`
    : null;

  // Tick once a second while something time-dependent is on screen.
  useEffect(() => {
    if (phase === 'idle') return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [phase]);

  // Leaving mid-download loses the portal session; ask before navigating away.
  useEffect(() => {
    if (!busy) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [busy]);

  useEffect(() => () => abortRef.current?.abort(), []);

  useEffect(() => {
    if (phase === 'captcha') inputRef.current?.focus();
  }, [phase, image]);

  const startPhase = (p: Phase) => {
    const t = Date.now();
    setPhase(p);
    setPhaseStartedAt(t);
    setNow(t);
  };

  const showCaptcha = (newToken: string, newImage: string, message?: string) => {
    setToken(newToken);
    setImage(newImage);
    setAnswer('');
    setNotice(message || null);
    setExpiresAt(Date.now() + CAPTCHA_LIFETIME_MS);
    startPhase('captcha');
  };

  const reset = () => {
    setToken(null);
    setImage(null);
    setAnswer('');
    setNotice(null);
    setExpiresAt(null);
    setAttempts(0);
    setPhase('idle');
  };

  const callBackend = async (url: string, init: RequestInit, timeoutMs: number): Promise<BackendResponse> => {
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    try {
      const res = await fetchWithAuth(url, { ...init, signal: controller.signal });
      let data: BackendResponse | null = null;
      try { data = (await res.json()) as BackendResponse; } catch { data = null; }
      if (!data) {
        throw new Error(res.status === 401 || res.status === 403
          ? 'You are not allowed to fetch portal documents. Ask an Admin or your MIS team.'
          : `The server returned an unexpected response (HTTP ${res.status}).`);
      }
      return data;
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'AbortError') {
        throw new Error('The portal took too long to respond. It may be busy; try again in a few minutes.');
      }
      if (e instanceof TypeError) {
        throw new Error('Could not reach the TenderPocket server. Check your connection and try again.');
      }
      throw e;
    } finally {
      clearTimeout(timer);
    }
  };

  const finish = async (data: PortalDocsResult) => {
    const count = data.files?.length;
    showToast(data.message || `Downloaded ${count ?? ''} document file(s) from the portal.`, 'success');
    reset();
    await onDownloaded(data);
  };

  const loadCaptcha = async () => {
    if (busy) return;
    setError(null);
    setNotice(null);
    setAnswer('');
    startPhase('opening');
    try {
      const data = await callBackend(`/api/tenders/${encodeURIComponent(tenderId)}/portal-documents/captcha`,
        { method: 'POST' }, OPEN_TIMEOUT_MS);
      if (data.status === 'CAPTCHA_REQUIRED' && data.token && data.captchaImage) {
        showCaptcha(data.token, data.captchaImage, data.message);
      } else if (data.status === 'DOWNLOADED') {
        await finish(data);
      } else {
        reset();
        setError(data.error || data.message || 'Could not open the tender on the portal.');
      }
    } catch (e: unknown) {
      reset();
      setError(errorMessage(e, 'Could not open the tender on the portal.'));
    }
  };

  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (busy || !token) return;
    if (!trimmed) {
      setError('Type the characters shown in the image first.');
      inputRef.current?.focus();
      return;
    }
    if (expired) {
      setError('This CAPTCHA has expired. Load a new image.');
      return;
    }
    setError(null);
    setAttempts((a) => a + 1);
    startPhase('submitting');
    try {
      const data = await callBackend(
        `/api/tenders/${encodeURIComponent(tenderId)}/portal-documents/captcha/${encodeURIComponent(token)}`,
        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ answer: trimmed }) },
        SUBMIT_TIMEOUT_MS);
      if (data.status === 'DOWNLOADED') {
        await finish(data);
      } else if (data.status === 'CAPTCHA_REQUIRED' && data.token && data.captchaImage) {
        showCaptcha(data.token, data.captchaImage, data.message || 'That CAPTCHA was not accepted. Type the new one.');
      } else {
        reset();
        setError(data.error || data.message || 'The download failed. Load a new CAPTCHA and try again.');
      }
    } catch (err: unknown) {
      reset();
      setError(errorMessage(err, 'The download failed. Load a new CAPTCHA and try again.'));
    }
  };

  const cancel = () => {
    abortRef.current?.abort();
    reset();
  };

  const errorBox = error && (
    <div role="alert" style={{ marginTop: '12px', display: 'flex', alignItems: 'flex-start', gap: '8px', padding: '10px 12px', borderRadius: '8px', background: 'rgba(239, 68, 68, 0.08)', border: '1px solid rgba(239, 68, 68, 0.3)', color: '#ef4444', fontSize: '12px', lineHeight: 1.5 }}>
      <AlertCircle size={15} style={{ flexShrink: 0, marginTop: '1px' }} />
      <span style={{ flex: 1 }}>{error}</span>
      {phase === 'idle' && (
        <button type="button" className="btn btn-secondary" onClick={loadCaptcha} style={{ padding: '4px 10px', fontSize: '11px' }}>
          Try again
        </button>
      )}
    </div>
  );

  const progress = (text: string, hint: string) => (
    <div role="status" aria-live="polite" style={{ marginTop: '12px', display: 'flex', alignItems: 'flex-start', gap: '10px', padding: '12px 14px', borderRadius: '8px', background: 'rgba(99, 102, 241, 0.06)', border: '1px solid var(--border-color)' }}>
      <RefreshCw size={16} className="animate-spin" style={{ color: 'var(--primary)', flexShrink: 0, marginTop: '1px' }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: '12.5px', fontWeight: 600, color: 'var(--text-primary)' }}>
          {text} <span style={{ fontWeight: 400, color: 'var(--text-muted)' }}>({elapsed}s)</span>
        </div>
        <div style={{ marginTop: '3px', fontSize: '11px', color: 'var(--text-muted)' }}>{hint}</div>
      </div>
      <button type="button" className="btn btn-secondary" onClick={cancel} style={{ padding: '4px 10px', fontSize: '11px' }}>
        Cancel
      </button>
    </div>
  );

  // Compact row once documents exist and nothing is in progress.
  if (hasPortalDocs && phase === 'idle') {
    return (
      <div style={{ marginBottom: '14px' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', fontSize: '12px', color: 'var(--text-secondary)' }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <CheckCircle2 size={14} style={{ color: '#10b981' }} /> Tender documents downloaded from the {portalLabel}.
          </span>
          <button type="button" className="btn btn-secondary" onClick={loadCaptcha} style={{ padding: '6px 12px', fontSize: '11px', whiteSpace: 'nowrap' }}>
            Re-download from portal
          </button>
        </div>
        {errorBox}
      </div>
    );
  }

  return (
    <div style={{ marginBottom: '16px', padding: '16px', borderRadius: '10px', border: `1px dashed ${STATE_PORTAL_COLOR}`, background: 'rgba(245, 158, 11, 0.06)' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: '10px' }}>
        <Download size={18} style={{ color: STATE_PORTAL_COLOR, flexShrink: 0, marginTop: '2px' }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <p style={{ margin: 0, fontSize: '13px', fontWeight: 700, color: 'var(--text-primary)' }}>
            Tender documents are on the {portalLabel}
          </p>
          <p style={{ margin: '4px 0 0 0', fontSize: '12px', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
            {needsCaptcha
              ? 'The portal asks for a CAPTCHA before it releases the documents. Load it, type the characters you see, and the notice, tender document and BOQ will be saved here, ready for technical specification work.'
              : 'This portal releases documents without a CAPTCHA. Download them once and the notice, tender document and BOQ will be saved here, ready for technical specification work.'}
          </p>

          {phase === 'idle' && (
            <button type="button" className="btn btn-primary" onClick={loadCaptcha} style={{ marginTop: '12px', padding: '8px 16px', fontSize: '12px' }}>
              {needsCaptcha ? 'Load CAPTCHA to fetch documents' : 'Download documents from portal'}
            </button>
          )}

          {phase === 'opening' && progress(
            stepText(OPENING_STEPS, elapsed),
            needsCaptcha
              ? 'Opening the tender on the government portal. Please keep this page open.'
              : 'Downloading the documents from the government portal. Please keep this page open.'
          )}

          {(phase === 'captcha' || phase === 'submitting') && image && (
            <form onSubmit={submit} style={{ marginTop: '12px' }}>
              <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: '10px', opacity: phase === 'submitting' ? 0.55 : 1 }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={image} alt="CAPTCHA from the eProcurement portal"
                  style={{ height: '46px', borderRadius: '6px', border: '1px solid var(--border-color)', background: '#fff', filter: expired ? 'grayscale(1)' : undefined }} />
                <input
                  ref={inputRef}
                  type="text"
                  value={answer}
                  onChange={(e) => { setAnswer(e.target.value.replace(/\s/g, '')); if (error) setError(null); }}
                  placeholder="Type the characters"
                  aria-label="CAPTCHA characters"
                  aria-invalid={!!lengthWarning}
                  autoComplete="off"
                  autoCapitalize="off"
                  autoCorrect="off"
                  spellCheck={false}
                  maxLength={10}
                  disabled={phase === 'submitting' || expired}
                  style={{ width: '170px', padding: '9px 12px', fontSize: '15px', fontFamily: 'monospace', letterSpacing: '3px', borderRadius: '6px', border: `1px solid ${lengthWarning ? STATE_PORTAL_COLOR : 'var(--border-color)'}`, background: 'var(--bg-input, transparent)', color: 'var(--text-primary)' }}
                />
                <button type="submit" className="btn btn-primary" disabled={phase === 'submitting' || !trimmed || expired}
                  style={{ padding: '8px 16px', fontSize: '12px', display: 'inline-flex', alignItems: 'center', gap: '6px' }}>
                  {phase === 'submitting' ? (<><RefreshCw size={13} className="animate-spin" /> Downloading…</>) : 'Download documents'}
                </button>
                <button type="button" className="btn btn-secondary" onClick={loadCaptcha} disabled={phase === 'submitting'}
                  style={{ padding: '8px 12px', fontSize: '12px' }}>
                  New image
                </button>
              </div>

              {phase === 'captcha' && (
                <div style={{ marginTop: '8px', display: 'flex', flexWrap: 'wrap', gap: '6px 16px', fontSize: '11px', color: 'var(--text-muted)' }}>
                  <span>Type exactly what you see, including capital and small letters.</span>
                  {expiresAt && !expired && (
                    <span style={{ color: expiringSoon ? STATE_PORTAL_COLOR : undefined, fontWeight: expiringSoon ? 600 : undefined }}>
                      Expires in {formatClock(remainingMs)}
                    </span>
                  )}
                  {attempts > 0 && <span>Attempt {attempts + 1}</span>}
                </div>
              )}
              {phase === 'captcha' && lengthWarning && (
                <div style={{ marginTop: '6px', fontSize: '11.5px', color: STATE_PORTAL_COLOR }}>{lengthWarning}</div>
              )}
              {expired && (
                <div style={{ marginTop: '8px', fontSize: '12px', color: STATE_PORTAL_COLOR, fontWeight: 600 }}>
                  This CAPTCHA has expired. Click “New image” to get a fresh one.
                </div>
              )}
              {phase === 'captcha' && notice && !expired && (
                <div style={{ marginTop: '8px', display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12px', color: attempts > 0 ? STATE_PORTAL_COLOR : 'var(--text-secondary)' }}>
                  {attempts > 0 && <AlertCircle size={13} />} {notice}
                </div>
              )}
              {phase === 'captcha' && attempts >= 3 && (
                <div style={{ marginTop: '6px', fontSize: '11px', color: 'var(--text-muted)' }}>
                  Several tries have not worked. If you are sure the characters are right, the portal may be having trouble; try again later or download from the portal directly.
                </div>
              )}
            </form>
          )}

          {phase === 'submitting' && progress(
            stepText(SUBMITTING_STEPS, elapsed),
            'Do not close or refresh this page until the download finishes.'
          )}

          {errorBox}
        </div>
      </div>
    </div>
  );
}
