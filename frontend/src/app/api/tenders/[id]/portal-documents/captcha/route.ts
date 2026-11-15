import { proxyToBackend } from '@/lib/backendProxy';

// Opens a state-portal tender on its portal and returns the portal's CAPTCHA image for the executive.
export async function POST(request: Request, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return proxyToBackend(request, `/api/tenders/${encodeURIComponent(id)}/portal-documents/captcha`);
}
