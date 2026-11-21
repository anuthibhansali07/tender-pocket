import { proxyToBackend } from '@/lib/backendProxy';

export async function GET(request: Request) {
  return proxyToBackend(request, '/api/tenders/fetch-status');
}
