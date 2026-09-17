import type {
  AddCommentRequest,
  Adjudication,
  ClaimAnomalySignal,
  ApiError,
  AssignableUser,
  AssignMemberRequest,
  AssignmentCandidate,
  AssignRequest,
  AddPlanExclusionRequest,
  AddFeeScheduleRequest,
  AddPriorAuthRequirementRequest,
  AuditAction,
  AuditChainVerification,
  AuditEvent,
  BreakGlassGrant,
  BreakGlassGrantAdmin,
  CreateBreakGlassRequest,
  DeadLetterEvent,
  Claim,
  ClaimStatusChange,
  ClaimStatusHistory,
  ClaimSummary,
  ClaimStatus,
  CoveragePlan,
  CreateClaimRequest,
  CreateCoveragePlanRequest,
  ConsentDirective,
  EnrollEligibilityRequest,
  MedicalCode,
  PageResponse,
  PatientEligibility,
  PlanExclusion,
  PlanFeeScheduleEntry,
  PlanPriorAuthRequirement,
  PlanNetworkProvider,
  AddNetworkProviderRequest,
  CreatePriorAuthorizationRequest,
  PriorAuthorization,
  PriorAuthorizationStatus,
  PriorAuthorizationSummary,
  PriorAuthStatusChange,
  PriorAuthStatusHistory,
  CoordinatorAssignment,
  CurrentUser,
  Patient,
  PatientCreateRequest,
  PatientDocument,
  Provider,
  ProviderAssignment,
  RecordConsentRequest,
  Referral,
  ReferralStatus,
  ReferralSummary,
  ReferralStatusChange,
  ReferralStatusHistory,
  CreateReferralRequest,
  Appeal,
  AppealStatus,
  AppealSummary,
  AppealStatusChange,
  AppealStatusHistory,
  CreateAppealRequest,
  ClaimReview,
  ClaimReviewStatus,
  ClaimReviewSummary,
  ClaimReviewStatusChange,
  ClaimReviewStatusHistory,
  CreateClaimReviewRequest,
  ReprocessingBatch,
  ReprocessingBatchSummary,
  ReprocessingBatchStatus,
  CreateReprocessingBatchRequest,
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

  /** The caller's tenant's active providers — the rendering-provider picker source (claim-create roles). */
  listProviders: () => request<Provider[]>('/api/v1/providers'),

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

  // --- Documents (§19) ----------------------------------------------------

  listDocuments: (patientId: string) =>
    request<PatientDocument[]>(`/api/v1/patients/${patientId}/documents`),

  // Multipart upload: send FormData WITHOUT a Content-Type header so the browser sets the
  // multipart boundary; the CSRF header is still injected by `request` (it's a POST).
  uploadDocument: (patientId: string, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request<PatientDocument>(`/api/v1/patients/${patientId}/documents`, {
      method: 'POST',
      body: form,
    })
  },

  // Download the bytes as a Blob. Non-2xx (e.g. a quarantined 409) throws ApiClientError, so the
  // caller shows the backend message instead of navigating to a JSON error page.
  downloadDocument: (patientId: string, documentId: string) =>
    downloadBlob(`/api/v1/patients/${patientId}/documents/${documentId}/content`),

  // Export the tenant's patient list as a CSV blob (§Phase 9 — reporting). The backend scopes the file to what
  // this caller may see and masks fields exactly as the JSON read does, so the download is safe to trigger for any
  // viewer. GET (no CSRF header needed); errors surface as ApiClientError like any other download.
  exportPatientsCsv: () => downloadBlob('/api/v1/patients/export.csv'),

  // --- Claims (§Phase 4) + adjudication (§Phase 5) ---
  // A page of the claims work queue (§Phase 9) — returns the full PageResponse envelope so the UI can render
  // page controls, totals, and sort state. Used by the claims queue page.
  listClaimsPage: (params: {
    patientId?: string
    status?: ClaimStatus
    q?: string
    page?: number
    size?: number
    sort?: string
  }) => {
    const qs = new URLSearchParams()
    if (params.patientId) qs.set('patientId', params.patientId)
    if (params.status) qs.set('status', params.status)
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<ClaimSummary>>(`/api/v1/claims?${qs.toString()}`)
  },

  // A convenience shim over the paged endpoint that returns just the rows, for callers that need the whole list
  // (name resolution, populating <select>s) rather than a page. Requests one large page and unwraps `.content`.
  listClaims: async (params?: { patientId?: string; status?: ClaimStatus }) => {
    const q = new URLSearchParams()
    if (params?.patientId) q.set('patientId', params.patientId)
    if (params?.status) q.set('status', params.status)
    q.set('size', '200')
    const page = await request<PageResponse<ClaimSummary>>(`/api/v1/claims?${q.toString()}`)
    return page.content
  },

  createClaim: (body: CreateClaimRequest) =>
    request<Claim>('/api/v1/claims', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  getClaim: (id: string) => request<Claim>(`/api/v1/claims/${id}`),

  searchMedicalCodes: (params?: { system?: string; q?: string }) => {
    const s = new URLSearchParams()
    if (params?.system) s.set('system', params.system)
    if (params?.q) s.set('q', params.q)
    const suffix = s.toString() ? `?${s.toString()}` : ''
    return request<MedicalCode[]>(`/api/v1/medical-codes${suffix}`)
  },

  getClaimHistory: (id: string) => request<ClaimStatusHistory[]>(`/api/v1/claims/${id}/history`),

  changeClaimStatus: (id: string, body: ClaimStatusChange) =>
    request<Claim>(`/api/v1/claims/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  adjudicateClaim: (id: string) =>
    request<Adjudication>(`/api/v1/claims/${id}/adjudicate`, { method: 'POST' }),

  getAdjudication: (id: string) => request<Adjudication>(`/api/v1/claims/${id}/adjudication`),

  getAdjudicationVersions: (id: string) =>
    request<Adjudication[]>(`/api/v1/claims/${id}/adjudication/versions`),

  // --- Claim anomaly signals (§Phase 6) ---
  scanClaimAnomalies: (id: string) =>
    request<ClaimAnomalySignal[]>(`/api/v1/claims/${id}/anomaly-scan`, { method: 'POST' }),

  listClaimAnomalies: (id: string) =>
    request<ClaimAnomalySignal[]>(`/api/v1/claims/${id}/anomalies`),

  // --- Prior authorization (§Phase 6) ---
  // A page of the prior-auth work queue (§Phase 9) — returns the full PageResponse envelope for page controls,
  // totals and sort state. Nothing else consumes this list, so there is no array shim (unlike claims).
  listPriorAuthorizations: (params: {
    patientId?: string
    status?: PriorAuthorizationStatus
    q?: string
    page?: number
    size?: number
    sort?: string
  }) => {
    const qs = new URLSearchParams()
    if (params.patientId) qs.set('patientId', params.patientId)
    if (params.status) qs.set('status', params.status)
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<PriorAuthorizationSummary>>(`/api/v1/prior-authorizations?${qs.toString()}`)
  },

  createPriorAuthorization: (body: CreatePriorAuthorizationRequest) =>
    request<PriorAuthorization>('/api/v1/prior-authorizations', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  getPriorAuthorization: (id: string) =>
    request<PriorAuthorization>(`/api/v1/prior-authorizations/${id}`),

  getPriorAuthHistory: (id: string) =>
    request<PriorAuthStatusHistory[]>(`/api/v1/prior-authorizations/${id}/history`),

  changePriorAuthStatus: (id: string, body: PriorAuthStatusChange) =>
    request<PriorAuthorization>(`/api/v1/prior-authorizations/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // --- Referrals (§Phase 6) ---
  // A page of the referral work queue (§Phase 9) — returns the full PageResponse envelope. Nothing else consumes
  // this list, so there is no array shim (like prior-auth, unlike claims).
  listReferrals: (params: {
    patientId?: string
    status?: ReferralStatus
    q?: string
    page?: number
    size?: number
    sort?: string
  }) => {
    const qs = new URLSearchParams()
    if (params.patientId) qs.set('patientId', params.patientId)
    if (params.status) qs.set('status', params.status)
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<ReferralSummary>>(`/api/v1/referrals?${qs.toString()}`)
  },

  createReferral: (body: CreateReferralRequest) =>
    request<Referral>('/api/v1/referrals', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  getReferral: (id: string) => request<Referral>(`/api/v1/referrals/${id}`),

  getReferralHistory: (id: string) =>
    request<ReferralStatusHistory[]>(`/api/v1/referrals/${id}/history`),

  changeReferralStatus: (id: string, body: ReferralStatusChange) =>
    request<Referral>(`/api/v1/referrals/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // --- Appeals (§Phase 6) ---
  // A page of the appeal work queue (§Phase 9) — returns the full PageResponse envelope (no array shim needed).
  listAppeals: (params: {
    claimId?: string
    status?: AppealStatus
    q?: string
    page?: number
    size?: number
    sort?: string
  }) => {
    const qs = new URLSearchParams()
    if (params.claimId) qs.set('claimId', params.claimId)
    if (params.status) qs.set('status', params.status)
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<AppealSummary>>(`/api/v1/appeals?${qs.toString()}`)
  },

  createAppeal: (body: CreateAppealRequest) =>
    request<Appeal>('/api/v1/appeals', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  getAppeal: (id: string) => request<Appeal>(`/api/v1/appeals/${id}`),

  getAppealHistory: (id: string) =>
    request<AppealStatusHistory[]>(`/api/v1/appeals/${id}/history`),

  changeAppealStatus: (id: string, body: AppealStatusChange) =>
    request<Appeal>(`/api/v1/appeals/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // --- Claim manual review (§Phase 6) ---
  // A page of the manual-review work queue (§Phase 9) — returns the full PageResponse envelope (no array shim).
  listClaimReviews: (params: {
    claimId?: string
    status?: ClaimReviewStatus
    q?: string
    page?: number
    size?: number
    sort?: string
  }) => {
    const qs = new URLSearchParams()
    if (params.claimId) qs.set('claimId', params.claimId)
    if (params.status) qs.set('status', params.status)
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<ClaimReviewSummary>>(`/api/v1/claim-reviews?${qs.toString()}`)
  },

  createClaimReview: (body: CreateClaimReviewRequest) =>
    request<ClaimReview>('/api/v1/claim-reviews', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  getClaimReview: (id: string) => request<ClaimReview>(`/api/v1/claim-reviews/${id}`),

  getClaimReviewHistory: (id: string) =>
    request<ClaimReviewStatusHistory[]>(`/api/v1/claim-reviews/${id}/history`),

  changeClaimReviewStatus: (id: string, body: ClaimReviewStatusChange) =>
    request<ClaimReview>(`/api/v1/claim-reviews/${id}/status`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // --- Reprocessing batches (§Phase 6) ---
  // A page of the reprocessing work queue (§Phase 9) — returns the full PageResponse envelope (no array shim).
  listReprocessingBatches: (params: {
    status?: ReprocessingBatchStatus
    q?: string
    page?: number
    size?: number
    sort?: string
  }) => {
    const qs = new URLSearchParams()
    if (params.status) qs.set('status', params.status)
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<ReprocessingBatchSummary>>(`/api/v1/reprocessing-batches?${qs.toString()}`)
  },

  getReprocessingBatch: (id: string) =>
    request<ReprocessingBatch>(`/api/v1/reprocessing-batches/${id}`),

  runReprocessingBatch: (body: CreateReprocessingBatchRequest) =>
    request<ReprocessingBatch>('/api/v1/reprocessing-batches', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  // --- Security audit trail (§Phase 7) ---
  // A page of the audit trail (§Phase 9) — optional server-side action filter; returns the PageResponse envelope.
  listAuditEvents: (params: {
    action?: AuditAction
    q?: string
    page?: number
    size?: number
    sort?: string
  }) => {
    const qs = new URLSearchParams()
    if (params.action) qs.set('action', params.action)
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<AuditEvent>>(`/api/v1/audit-events?${qs.toString()}`)
  },

  verifyAuditChain: () => request<AuditChainVerification>('/api/v1/audit-events/verify'),

  // --- Break-glass emergency access (§Phase 7) ---
  listBreakGlass: () => request<BreakGlassGrant[]>('/api/v1/break-glass'),

  breakGlass: (body: CreateBreakGlassRequest) =>
    request<BreakGlassGrant>('/api/v1/break-glass', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  listAllBreakGlass: () => request<BreakGlassGrantAdmin[]>('/api/v1/break-glass/all'),

  revokeBreakGlass: (id: string) =>
    request<BreakGlassGrantAdmin>(`/api/v1/break-glass/${id}/revoke`, { method: 'POST' }),

  // --- Dead-letter inspection + replay (§Phase 8) ---
  // A page of the dead-letter queue (§Phase 9) — returns the full PageResponse envelope (no array shim).
  listDeadLetterEvents: (params: { q?: string; page?: number; size?: number; sort?: string }) => {
    const qs = new URLSearchParams()
    if (params.q) qs.set('q', params.q)
    if (params.page != null) qs.set('page', String(params.page))
    if (params.size != null) qs.set('size', String(params.size))
    if (params.sort) qs.set('sort', params.sort)
    return request<PageResponse<DeadLetterEvent>>(`/api/v1/dead-letter-events?${qs.toString()}`)
  },

  replayDeadLetterEvent: (id: string) =>
    request<DeadLetterEvent>(`/api/v1/dead-letter-events/${id}/replay`, { method: 'POST' }),

  // --- Coverage plans + exclusions (§Phase 4/5) ---
  listCoveragePlans: () => request<CoveragePlan[]>('/api/v1/coverage-plans'),

  getCoveragePlan: (id: string) => request<CoveragePlan>(`/api/v1/coverage-plans/${id}`),

  createCoveragePlan: (body: CreateCoveragePlanRequest) =>
    request<CoveragePlan>('/api/v1/coverage-plans', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  listExclusions: (planId: string) =>
    request<PlanExclusion[]>(`/api/v1/coverage-plans/${planId}/exclusions`),

  addExclusion: (planId: string, body: AddPlanExclusionRequest) =>
    request<PlanExclusion>(`/api/v1/coverage-plans/${planId}/exclusions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  removeExclusion: (planId: string, exclusionId: string) =>
    request<void>(`/api/v1/coverage-plans/${planId}/exclusions/${exclusionId}`, { method: 'DELETE' }),

  listFeeSchedule: (planId: string) =>
    request<PlanFeeScheduleEntry[]>(`/api/v1/coverage-plans/${planId}/fee-schedule`),

  addFeeSchedule: (planId: string, body: AddFeeScheduleRequest) =>
    request<PlanFeeScheduleEntry>(`/api/v1/coverage-plans/${planId}/fee-schedule`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  removeFeeSchedule: (planId: string, entryId: string) =>
    request<void>(`/api/v1/coverage-plans/${planId}/fee-schedule/${entryId}`, { method: 'DELETE' }),

  listPriorAuthRequirements: (planId: string) =>
    request<PlanPriorAuthRequirement[]>(`/api/v1/coverage-plans/${planId}/prior-auth-requirements`),

  addPriorAuthRequirement: (planId: string, body: AddPriorAuthRequirementRequest) =>
    request<PlanPriorAuthRequirement>(`/api/v1/coverage-plans/${planId}/prior-auth-requirements`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  removePriorAuthRequirement: (planId: string, requirementId: string) =>
    request<void>(`/api/v1/coverage-plans/${planId}/prior-auth-requirements/${requirementId}`, {
      method: 'DELETE',
    }),

  // --- Plan network providers (§Phase 6 provider network) ---
  listNetworkProviders: (planId: string) =>
    request<PlanNetworkProvider[]>(`/api/v1/coverage-plans/${planId}/network-providers`),

  listNetworkProviderCandidates: (planId: string) =>
    request<AssignmentCandidate[]>(`/api/v1/coverage-plans/${planId}/network-providers/candidates`),

  addNetworkProvider: (planId: string, body: AddNetworkProviderRequest) =>
    request<PlanNetworkProvider>(`/api/v1/coverage-plans/${planId}/network-providers`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),

  removeNetworkProvider: (planId: string, networkProviderId: string) =>
    request<void>(`/api/v1/coverage-plans/${planId}/network-providers/${networkProviderId}`, {
      method: 'DELETE',
    }),

  // --- Patient eligibility (§Phase 4/5) ---
  listEligibility: (patientId: string) =>
    request<PatientEligibility[]>(`/api/v1/patients/${patientId}/eligibility`),

  enrollEligibility: (patientId: string, body: EnrollEligibilityRequest) =>
    request<PatientEligibility>(`/api/v1/patients/${patientId}/eligibility`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    }),
}

/** GET a URL and return its response body as a Blob, throwing {@link ApiClientError} on a non-2xx. */
async function downloadBlob(path: string): Promise<Blob> {
  const response = await fetch(path, { credentials: 'same-origin' })
  if (!response.ok) {
    let body: ApiError | null = null
    try {
      body = (await response.json()) as ApiError
    } catch {
      // non-JSON error body; leave as null
    }
    throw new ApiClientError(response.status, body)
  }
  return response.blob()
}
