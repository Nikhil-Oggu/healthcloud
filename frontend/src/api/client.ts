import type {
  AddCommentRequest,
  ApiError,
  AssignableUser,
  AssignMemberRequest,
  AssignmentCandidate,
  AssignRequest,
  ConsentDirective,
  CoordinatorAssignment,
  CurrentUser,
  Patient,
  PatientCreateRequest,
  ProviderAssignment,
  RecordConsentRequest,
  RequestAssignment,
  RequestComment,
  RequestStatusHistory,
  ServiceRequest,
  ServiceRequestCreateRequest,
  StatusChangeRequest,
} from './types'

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

  getPatient: (id: string) => request<Patient>(`/api/v1/patients/${id}`),

  createPatient: (body: PatientCreateRequest) =>
    request<Patient>('/api/v1/patients', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  listRequests: (patientId?: string) =>
    request<ServiceRequest[]>('/api/v1/requests' + (patientId ? `?patientId=${patientId}` : '')),

  getRequest: (id: string) => request<ServiceRequest>(`/api/v1/requests/${id}`),

  createRequest: (body: ServiceRequestCreateRequest) =>
    request<ServiceRequest>('/api/v1/requests', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  changeRequestStatus: (id: string, body: StatusChangeRequest) =>
    request<ServiceRequest>(`/api/v1/requests/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  getRequestHistory: (id: string) =>
    request<RequestStatusHistory[]>(`/api/v1/requests/${id}/history`),

  listComments: (id: string) =>
    request<RequestComment[]>(`/api/v1/requests/${id}/comments`),

  addComment: (id: string, body: AddCommentRequest) =>
    request<RequestComment>(`/api/v1/requests/${id}/comments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  getAssignment: (id: string) =>
    request<RequestAssignment | null>(`/api/v1/requests/${id}/assignment`),

  listAssignableUsers: (id: string) =>
    request<AssignableUser[]>(`/api/v1/requests/${id}/assignable-users`),

  assign: (id: string, body: AssignRequest) =>
    request<RequestAssignment>(`/api/v1/requests/${id}/assignment`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  listConsentDirectives: (patientId: string) =>
    request<ConsentDirective[]>(`/api/v1/patients/${patientId}/consent-directives`),

  recordConsent: (patientId: string, body: RecordConsentRequest) =>
    request<ConsentDirective>(`/api/v1/patients/${patientId}/consent-directives`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  revokeConsent: (patientId: string, directiveId: string, expectedVersion: number) =>
    request<ConsentDirective>(
      `/api/v1/patients/${patientId}/consent-directives/${directiveId}/revoke`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedVersion }),
      },
    ),

  // --- Care-team assignments (§14.3) --------------------------------------

  listProviderAssignments: (patientId: string) =>
    request<ProviderAssignment[]>(`/api/v1/patients/${patientId}/provider-assignments`),

  listProviderCandidates: (patientId: string) =>
    request<AssignmentCandidate[]>(`/api/v1/patients/${patientId}/provider-assignments/candidates`),

  assignProvider: (patientId: string, body: AssignMemberRequest) =>
    request<ProviderAssignment>(`/api/v1/patients/${patientId}/provider-assignments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        providerUserId: body.userId,
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
      }),
    }),

  revokeProviderAssignment: (patientId: string, assignmentId: string, expectedVersion: number) =>
    request<ProviderAssignment>(
      `/api/v1/patients/${patientId}/provider-assignments/${assignmentId}/revoke`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedVersion }),
      },
    ),

  listCoordinatorAssignments: (patientId: string) =>
    request<CoordinatorAssignment[]>(`/api/v1/patients/${patientId}/coordinator-assignments`),

  listCoordinatorCandidates: (patientId: string) =>
    request<AssignmentCandidate[]>(`/api/v1/patients/${patientId}/coordinator-assignments/candidates`),

  assignCoordinator: (patientId: string, body: AssignMemberRequest) =>
    request<CoordinatorAssignment>(`/api/v1/patients/${patientId}/coordinator-assignments`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        coordinatorUserId: body.userId,
        effectiveFrom: body.effectiveFrom,
        effectiveTo: body.effectiveTo,
      }),
    }),

  revokeCoordinatorAssignment: (patientId: string, assignmentId: string, expectedVersion: number) =>
    request<CoordinatorAssignment>(
      `/api/v1/patients/${patientId}/coordinator-assignments/${assignmentId}/revoke`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ expectedVersion }),
      },
    ),
}
