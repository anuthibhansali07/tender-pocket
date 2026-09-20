import { getAuthFromRequest } from '@/lib/auth';

export type SpecificationPayload = Record<string, unknown>;

export class SpecificationProxyError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export function isObject(value: unknown): value is SpecificationPayload {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

export function requirePathSegment(value: string): string {
  if (!value || value === '.' || value === '..' || /[/\\\u0000-\u001f]/.test(value)) {
    throw new SpecificationProxyError(400, 'Invalid tender ID or document filename.');
  }
  return value;
}

export function localDocumentUrl(id: string, filename: string): string {
  requirePathSegment(id);
  requirePathSegment(filename);
  return `/api/tenders/${encodeURIComponent(id)}/documents/${encodeURIComponent(filename)}`;
}

export function specificationBackendUrl(request: Request): string {
  const configured = process.env.BACKEND_URL || 'http://localhost:8090';
  let url: URL;
  try {
    url = new URL(configured);
  } catch {
    throw new SpecificationProxyError(503, 'The technical specification backend URL is invalid.');
  }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password
      || url.search || url.hash || (url.origin === new URL(request.url).origin && url.pathname === '/')) {
    throw new SpecificationProxyError(503, 'The technical specification backend URL is invalid or points to the frontend.');
  }
  return url.toString().replace(/\/+$/, '');
}

export function fetchSpecificationBackend(request: Request, resource: string, init: RequestInit = {}) {
  const auth = getAuthFromRequest(request);
  const headers = new Headers(init.headers);
  headers.set('x-user-role', auth?.role || request.headers.get('x-user-role') || 'User');
  headers.set('x-user-username', auth?.username || request.headers.get('x-user-username') || 'system');
  const authorization = request.headers.get('authorization');
  if (authorization) headers.set('authorization', authorization);
  if (!headers.has('Accept')) headers.set('Accept', 'application/json');

  // Java runs conversion synchronously. Do not impose the old optional-sync 3s timer,
  // retry a paid conversion automatically, or pretend that a disconnected request cancelled Java.
  request.signal.throwIfAborted();
  return fetch(`${specificationBackendUrl(request)}${resource}`, {
    ...init, headers, cache: 'no-store', signal: request.signal, redirect: 'error',
  });
}

export async function readSpecificationResponse(response: Response): Promise<SpecificationPayload> {
  const body = await response.text();
  let result: unknown;
  try {
    result = JSON.parse(body);
  } catch {
    if (!response.ok) {
      const plainText = response.headers.get('content-type')?.includes('text/plain') ? body.trim().slice(0, 500) : '';
      return { success: false, error: plainText || `The specification backend returned HTTP ${response.status}.` };
    }
    throw new SpecificationProxyError(502, 'The specification backend returned an invalid conversion response.');
  }
  if (!isObject(result)) {
    throw new SpecificationProxyError(502, 'The specification backend returned an invalid conversion response.');
  }
  return result;
}

export function validateConversionResult(result: SpecificationPayload) {
  if (result.success !== true || typeof result.generated !== 'boolean' || !Array.isArray(result.products)) {
    throw new SpecificationProxyError(502, 'The backend did not return a completed specification conversion result.');
  }
  if (result.generated) {
    if (!result.products.length || !result.products.every(product => isObject(product)
        && typeof product.pdfDownloadUrl === 'string' && typeof product.docxDownloadUrl === 'string'
        && typeof product.xlsxDownloadUrl === 'string')
        || typeof result.pdfDownloadUrl !== 'string' || typeof result.docxDownloadUrl !== 'string'
        || typeof result.xlsxDownloadUrl !== 'string') {
      throw new SpecificationProxyError(502, 'The backend reported generated sheets without their download links.');
    }
  } else if (result.products.length || result.pdfDownloadUrl != null || result.docxDownloadUrl != null
      || result.xlsxDownloadUrl != null) {
    throw new SpecificationProxyError(502, 'The backend returned contradictory no-specifications information.');
  }
}

export function mapSpecificationDownloads(result: SpecificationPayload, request: Request, id: string): SpecificationPayload {
  const backend = specificationBackendUrl(request);
  const rootPrefix = `/documents/${encodeURIComponent(id)}/`;
  const prefixedPath = new URL(`${backend}${rootPrefix}`).pathname;
  const mapUrl = (value: unknown, extension: string) => {
    if (value == null) return value;
    if (typeof value !== 'string' || !value) throw new SpecificationProxyError(502, 'Invalid generated-document link.');
    let url: URL;
    try {
      url = new URL(value, `${backend}/`);
    } catch {
      throw new SpecificationProxyError(502, 'Invalid generated-document link.');
    }
    const prefix = url.pathname.startsWith(prefixedPath) ? prefixedPath : rootPrefix;
    if (url.origin !== new URL(backend).origin || !url.pathname.startsWith(prefix)) {
      throw new SpecificationProxyError(502, 'The backend returned a document link outside this tender.');
    }
    let filename: string;
    try {
      filename = decodeURIComponent(url.pathname.slice(prefix.length));
      requirePathSegment(filename);
    } catch {
      throw new SpecificationProxyError(502, 'The backend returned an invalid generated-document filename.');
    }
    if (!filename.toLowerCase().endsWith(extension)) {
      throw new SpecificationProxyError(502, 'The backend returned an unexpected generated-document type.');
    }
    return `/api/tenders/${encodeURIComponent(id)}/tech-spec-download/${encodeURIComponent(filename)}`;
  };
  const mapped: SpecificationPayload = { ...result };
  for (const [key, extension] of [['pdfDownloadUrl', '.pdf'], ['docxDownloadUrl', '.docx'],
    ['xlsxDownloadUrl', '.xlsx']]) {
    if (key in mapped) mapped[key] = mapUrl(mapped[key], extension);
  }
  if (Array.isArray(result.products)) {
    mapped.products = result.products.map(product => {
      if (!isObject(product)) throw new SpecificationProxyError(502, 'Invalid product in conversion response.');
      return { ...product, pdfDownloadUrl: mapUrl(product.pdfDownloadUrl, '.pdf'),
        docxDownloadUrl: mapUrl(product.docxDownloadUrl, '.docx'),
        xlsxDownloadUrl: mapUrl(product.xlsxDownloadUrl, '.xlsx') };
    });
  }
  return mapped;
}

export function specificationFailure(error: unknown, request: Request): Response {
  const detail = error as { name?: string; code?: string; cause?: { code?: string } };
  const code = detail?.cause?.code || detail?.code;
  let status = 502;
  let message = 'Could not reach the technical specification backend. Conversion success could not be confirmed.';
  if (error instanceof SpecificationProxyError) {
    status = error.status;
    message = error.message;
  } else if (request.signal.aborted) {
    status = 499;
    message = 'The upload request was cancelled. Java may still be processing; check conversion progress before retrying.';
  } else if (detail?.name === 'TimeoutError' || ['ETIMEDOUT', 'UND_ERR_CONNECT_TIMEOUT',
      'UND_ERR_HEADERS_TIMEOUT', 'UND_ERR_BODY_TIMEOUT'].includes(code || '')) {
    status = 504;
    message = 'The conversion response timed out. Java may still be processing; check conversion progress before retrying.';
  }
  return Response.json({ success: false, error: message }, { status, headers: { 'Cache-Control': 'no-store' } });
}
