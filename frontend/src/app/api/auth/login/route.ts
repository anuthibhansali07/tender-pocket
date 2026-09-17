import { NextResponse } from 'next/server';
import crypto from 'crypto';
import db, { hashPassword } from '@/lib/db';

const JWT_SECRET = process.env.JWT_SECRET || '9a6156a5c2d3a3f5a2f8c5b8e9b6a1c8d5e6f3b2a5c8d3e4f5a8b9c1d2e3f4a5';
const JWT_EXPIRATION_SECONDS = 315360000; // 10 years

/**
 * Generate an RFC 7519 compliant HMAC-SHA256 JWT token.
 * Compatible with Spring Boot's JwtUtil / JJWT parser.
 */
function createJwtToken(username: string, role: string): string {
  const header = Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url');
  const now = Math.floor(Date.now() / 1000);
  const payload = Buffer.from(
    JSON.stringify({
      sub: username,
      role: role,
      iat: now,
      exp: now + JWT_EXPIRATION_SECONDS
    })
  ).toString('base64url');

  const signature = crypto
    .createHmac('sha256', JWT_SECRET)
    .update(`${header}.${payload}`)
    .digest('base64url');

  return `${header}.${payload}.${signature}`;
}

export async function POST(request: Request) {
  try {
    const body = await request.json();
    const { username, password } = body;

    if (!username || !password) {
      return NextResponse.json(
        { success: false, error: 'Username and password are required' },
        { status: 400 }
      );
    }

    const trimmedUsername = username.trim().toLowerCase();

    // 1. Attempt backend authentication if BACKEND_URL is explicitly set and reachable
    const backendUrl = process.env.BACKEND_URL;
    if (backendUrl && backendUrl !== 'http://localhost:8080') {
      try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 2000);
        const backendResponse = await fetch(`${backendUrl}/api/auth/login`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
          body: JSON.stringify(body),
          cache: 'no-store',
          signal: controller.signal
        });
        clearTimeout(timeout);

        const contentType = backendResponse.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
          const data = await backendResponse.json();
          if (data.success && data.token) {
            return NextResponse.json(data, { status: backendResponse.status });
          }
        }
      } catch (backendErr) {
        // Backend unreachable — proceed to local database fallback
      }
    }

    // 2. Local Database Authentication (SQLite)
    const userStmt = db.prepare('SELECT username, password_hash, role FROM users WHERE LOWER(username) = ?');
    const user = userStmt.get(trimmedUsername) as { username: string; password_hash: string; role: string } | undefined;

    if (!user) {
      return NextResponse.json(
        { success: false, error: 'Invalid username or password' },
        { status: 401 }
      );
    }

    const inputHash = hashPassword(password);
    if (user.password_hash !== inputHash) {
      return NextResponse.json(
        { success: false, error: 'Invalid username or password' },
        { status: 401 }
      );
    }

    // 3. Issue cryptographically signed JWT token
    const token = createJwtToken(user.username, user.role);

    return NextResponse.json({
      success: true,
      token,
      user: {
        username: user.username,
        role: user.role
      }
    });

  } catch (error) {
    console.error('[login/route.ts] Authentication error:', error);
    return NextResponse.json(
      { success: false, error: error instanceof Error ? error.message : String(error) },
      { status: 500 }
    );
  }
}
