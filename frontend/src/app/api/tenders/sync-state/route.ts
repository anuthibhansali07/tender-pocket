import { proxyToBackend } from '@/lib/backendProxy';

// Starts the state & central eProcurement portal sync on the Spring backend (?portals=MH,UP&dryRun=true).
export async function POST(request: Request) {
  const { search } = new URL(request.url);
  return proxyToBackend(request, `/api/tenders/sync-state${search}`);
}
