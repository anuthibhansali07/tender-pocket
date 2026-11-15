import { proxyToBackend } from '@/lib/backendProxy';

// Submits the CAPTCHA the executive typed; the backend downloads and stores the tender documents.
export async function POST(request: Request, { params }: { params: Promise<{ id: string; token: string }> }) {
  const { id, token } = await params;
  return proxyToBackend(request, `/api/tenders/${encodeURIComponent(id)}/portal-documents/captcha/${encodeURIComponent(token)}`);
}
