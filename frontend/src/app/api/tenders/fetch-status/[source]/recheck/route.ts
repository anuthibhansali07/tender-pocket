import { proxyToBackend } from '@/lib/backendProxy';

export async function POST(
  request: Request,
  { params }: { params: Promise<{ source: string }> }
) {
  const { source } = await params;
  return proxyToBackend(request, `/api/tenders/fetch-status/${encodeURIComponent(source)}/recheck`);
}
