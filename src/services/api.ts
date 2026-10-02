/**
 * Centralised API client.
 *
 * <p>The access token is held **in memory only** — never written to localStorage — so an XSS
 * payload cannot read it at rest. Durability across reloads comes from the HttpOnly refresh
 * cookie, which page scripts cannot read; on boot the app silently calls `/refresh` to obtain a
 * new access token.
 *
 * <p>The base URL comes from `VITE_API_BASE_URL` so no environment URL is hardcoded.
 */

export const API_BASE_URL =
  (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? 'http://localhost:8080';

/** Shape of the backend's error body, so messages can be shown verbatim. */
export interface ApiErrorBody {
  status: number;
  code: string;
  message: string;
  fieldErrors?: Record<string, string>;
  timestamp?: string;
  path?: string;
}

/** Thrown for any non-2xx response. Carries the status so callers can branch on it. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors?: Record<string, string>;

  constructor(status: number, body: Partial<ApiErrorBody>) {
    super(body.message ?? `Request failed with status ${status}`);
    this.name = 'ApiError';
    this.status = status;
    this.code = body.code ?? 'UNKNOWN';
    this.fieldErrors = body.fieldErrors;
  }

  /** True when the session is gone and the user must sign in again. */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  /** True when the caller is authenticated but not allowed to do this. */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** True when the backend could not be reached at all. */
  get isNetworkError(): boolean {
    return this.status === 0;
  }
}

let accessToken: string | null = null;

/** The in-memory access token, or null when signed out. */
export function getAccessToken(): string | null {
  return accessToken;
}

/** Replaces the in-memory access token. Passing null signs the client out. */
export function setAccessToken(token: string | null): void {
  accessToken = token;
}

/** Called when the session is lost so the app can route back to sign-in. */
type UnauthorizedHandler = () => void;
let onUnauthorized: UnauthorizedHandler | null = null;

export function setUnauthorizedHandler(handler: UnauthorizedHandler | null): void {
  onUnauthorized = handler;
}

export interface AuthUser {
  userId: string;
  displayName: string;
  email?: string | null;
  roles: string[];
  restaurantId?: number | null;
  restaurantName?: string | null;
  creatorId?: number | null;
  creatorHandle?: string | null;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  userId: string;
  displayName: string;
  roles: string[];
  restaurantId?: number | null;
  creatorId?: number | null;
}

/**
 * Performs one API request.
 *
 * <p>Any non-2xx becomes an {@link ApiError}; a transport failure becomes one with status 0 so
 * the UI can show a connection error instead of silently falling back to mock data.
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, withCookie = false, signal } = options;

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  // Required by the backend for the cookie-authenticated refresh/logout routes.
  if (withCookie) headers[CSRF_HEADER] = '1';

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      signal,
      // Same-origin requests send cookies only when explicitly requested, which keeps the
      // refresh cookie off ordinary API calls.
      credentials: withCookie ? 'include' : 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (cause) {
    if ((cause as Error)?.name === 'AbortError') throw cause;
    throw new ApiError(0, {
      status: 0,
      code: 'NETWORK_ERROR',
      message:
        'Could not reach the Customer Trace API. Check that the backend is running and that ' +
        'VITE_API_BASE_URL points at it.',
    });
  }

  if (response.status === 204) return undefined as T;

  const text = await response.text();
  const parsed = text ? safeParse(text) : undefined;

  if (!response.ok) {
    const error = new ApiError(response.status, (parsed ?? {}) as Partial<ApiErrorBody>);
    // A 401 means the access token is gone or expired; let the app react once, centrally.
    if (error.isUnauthorized && !path.startsWith('/api/auth/')) {
      setAccessToken(null);
      onUnauthorized?.();
    }
    throw error;
  }

  return parsed as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => request<T>(path, { signal }),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  put: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  delete: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
  /** For the refresh/logout endpoints, which authenticate via the HttpOnly cookie. */
  postWithCookie: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body, withCookie: true }),
};

// ── Authentication ────────────────────────────────────────────────────────────

/** Signs in and stores the access token in memory. */
export async function login(
  userId: string,
  password: string,
  portal: string,
): Promise<LoginResponse> {
  const result = await request<LoginResponse>('/api/auth/login', {
    method: 'POST',
    body: { userId, password, portal },
  });
  setAccessToken(result.accessToken);
  return result;
}

/** Exchanges the HttpOnly refresh cookie for a new access token. */
export async function refreshSession(): Promise<LoginResponse | null> {
  try {
    const result = await request<LoginResponse>('/api/auth/refresh', {
      method: 'POST',
      withCookie: true,
    });
    setAccessToken(result.accessToken);
    return result;
  } catch {
    // No refresh cookie, or it expired: stay signed out.
    setAccessToken(null);
    return null;
  }
}

/** Revokes the refresh-token family and clears the in-memory token. */
export async function logout(): Promise<void> {
  try {
    await request<void>('/api/auth/logout', { method: 'POST', withCookie: true });
  } finally {
    setAccessToken(null);
  }
}

/** The authenticated principal, used to gate routes and navigation. */
export async function fetchCurrentUser(): Promise<AuthUser> {
  return request<AuthUser>('/api/auth/me');
}
/** The custom header the backend requires on cookie-authenticated calls. */
const CSRF_HEADER = 'X-CT-CSRF';

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  /** Sent with credentials so the refresh cookie travels with the request. */
  withCookie?: boolean;
  signal?: AbortSignal;
}