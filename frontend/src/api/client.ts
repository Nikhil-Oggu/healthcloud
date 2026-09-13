import type { ApiError, CurrentUser, Patient, PatientCreateRequest } from './types'

/**
 * Error thrown for any non-2xx response. Carries the parsed {@link ApiError} body (when present) so
 * the UI can show the backend's message and its correlationId for support/debugging.
 */
export class ApiClientError extends Error {
  readonly status: number
  readonly body: ApiError | null

  constructor(status: number, body: ApiError | null) {
    super(body?.message ?? `Request failed (${status})`)
    this.name = 'ApiClientError'
    this.status = status
    this.body = body
  }

  get code(): string | undefined {
    return this.body?.code
  }

  get correlationId(): string | undefined {
    return this.body?.correlationId
  }
}

/** Reads a browser cookie value by name (used for the readable XSRF-TOKEN cookie). */
function readCookie(name: string): string | null {
  const escaped = name.replace(/([.$?*|{}()[\]\\/+^])/g, '\\$1')
  const match = document.cookie.match(new RegExp('(?:^|; )' + escaped + '=([^;]*)'))
  return match ? decodeURIComponent(match[1]) : null
}

interface RequestOptions {
  method?: string
  body?: BodyInit | null
  headers?: Record<string, string>
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { ...options.headers }

  // CSRF: echo the readable XSRF-TOKEN cookie back as a header on state-changing requests.
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie('XSRF-TOKEN')
    if (token) {
      headers['X-XSRF-TOKEN'] = token
    }
  }

  const response = await fetch(path, {
    method,
    headers,
    body: options.body,
    credentials: 'same-origin', // send the session cookie (same-origin via the Vite proxy)
  })

  if (!response.ok) {
    let body: ApiError | null = null
    try {
      body = (await response.json()) as ApiError
    } catch {
      // non-JSON error body; leave as null
    }
    throw new ApiClientError(response.status, body)
  }

  if (response.status === 204) {
    return undefined as T
  }
  const text = await response.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** Typed API surface used by the app. */
export const api = {
  me: () => request<CurrentUser>('/api/v1/me'),

  devLogin: (email: string) =>
    request<void>('/api/v1/dev-login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ email }).toString(),
    }),

  logout: () => request<void>('/api/v1/logout', { method: 'POST' }),

  listPatients: () => request<Patient[]>('/api/v1/patients'),

  createPatient: (body: PatientCreateRequest) =>
    request<Patient>('/api/v1/patients', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
}
