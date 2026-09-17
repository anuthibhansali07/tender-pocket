import crypto from 'crypto';

// NOTE: Never commit a real secret here. Set JWT_SECRET in your .env.local / deployment environment.
// The fallback below is for local dev convenience only — override it in production.
const JWT_SECRET = process.env.JWT_SECRET || '9a6156a5c2d3a3f5a2f8c5b8e9b6a1c8d5e6f3b2a5c8d3e4f5a8b9c1d2e3f4a5';

export interface AuthInfo {
  username: string;
  role: string;
}

/**
 * Extracts and verifies the authenticated user from a Next.js Request.
 *
 * Priority:
 * 1. Bearer JWT in the Authorization header (signature-verified + expiry-checked)
 * 2. x-user-username + x-user-role headers (set by fetchWithAuth for same-origin requests)
 *
 * Returns null if no valid authentication is found.
 */
export function getAuthFromRequest(request: Request): AuthInfo | null {
  // 1. Try JWT Bearer token first
  const authHeader = request.headers.get('authorization');
  if (authHeader && authHeader.startsWith('Bearer ')) {
    const token = authHeader.substring(7);
    const parts = token.split('.');
    if (parts.length === 3) {
      try {
        // Detect algorithm from JWT header (supports HS256 and HS512)
        let alg = 'sha256';
        try {
          const headerObj = JSON.parse(Buffer.from(parts[0], 'base64url').toString('utf8'));
          if (headerObj.alg === 'HS512') alg = 'sha512';
        } catch (_) {
          // Malformed JWT header — default to sha256
        }

        const expectedSig = crypto
          .createHmac(alg, JWT_SECRET)
          .update(`${parts[0]}.${parts[1]}`)
          .digest('base64url');

        if (expectedSig === parts[2]) {
          const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
          if (payload.exp && payload.exp > Math.floor(Date.now() / 1000)) {
            return { username: payload.sub, role: payload.role };
          }
        }
      } catch (_) {
        // Invalid token — fall through to header fallback
      }
    }
  }

  // 2. Fallback: x-user-* headers (used for local testing and same-origin fetchWithAuth calls)
  // Security note: In strict production deployments where API routes are directly exposed
  // to untrusted public clients, set REQUIRE_JWT_AUTH=true to disable unverified header fallback.
  if (process.env.REQUIRE_JWT_AUTH === 'true') {
    return null;
  }

  const username = request.headers.get('x-user-username');
  const role = request.headers.get('x-user-role');
  if (username && role) {
    const validRoles = [
      'Admin', 'MIS Team', 'MIS Executive',
      'Clearance Team', 'Specification Team',
      'TPC Pricing Team', 'TPC Team',
      'Tender Executive', 'Executive', 'User'
    ];
    if (validRoles.includes(role)) {
      return { username, role };
    }
  }

  return null;
}

