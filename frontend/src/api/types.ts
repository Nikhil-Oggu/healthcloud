/** The authenticated user's context, as returned by GET /api/v1/me (mirrors CurrentUserDto). */
export interface CurrentUser {
  userId: string
  email: string
  fullName: string
  organizationId: string | null
  organizationName: string | null
  roles: string[]
}

/** The backend's single error shape: { code, message, correlationId, details }. */
export interface ApiError {
  code: string
  message: string
  correlationId?: string
  details?: unknown
}

export type PatientStatus = 'ACTIVE' | 'INACTIVE'

/** A patient profile, as returned by the patients API (mirrors PatientDto). */
export interface Patient {
  id: string
  medicalRecordNumber: string
  fullName: string
  dateOfBirth: string // ISO date (yyyy-mm-dd)
  status: PatientStatus
  version: number
}

/** Payload to create a patient. The tenant is stamped on the server, never sent by the client. */
export interface PatientCreateRequest {
  medicalRecordNumber: string
  fullName: string
  dateOfBirth: string // ISO date (yyyy-mm-dd)
}

export type ServiceRequestStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'TRIAGED'
  | 'ASSIGNED'
  | 'UNDER_REVIEW'
  | 'NEEDS_INFORMATION'
  | 'APPROVED'
  | 'REJECTED'
  | 'CANCELLED'
  | 'CLOSED'

export type ServiceRequestType =
  | 'CLAIM_SUPPORT'
  | 'REFERRAL_REQUEST'
  | 'DOCUMENT_REVIEW'
  | 'APPOINTMENT_HELP'
  | 'BENEFIT_CLARIFICATION'

export type ServiceRequestPriority = 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'

/** A service request (mirrors ServiceRequestDto). */
export interface ServiceRequest {
  id: string
  patientId: string
  type: ServiceRequestType
  status: ServiceRequestStatus
  priority: ServiceRequestPriority
  title: string
  description: string | null
  createdBy: string
  version: number
  createdAt: string
}

/** One status-history entry (mirrors RequestStatusHistoryDto). */
export interface RequestStatusHistory {
  id: string
  fromStatus: ServiceRequestStatus | null
  toStatus: ServiceRequestStatus
  actorUserId: string
  reason: string | null
  createdAt: string
}

/** Payload to create a request. Tenant + creator are set on the server. */
export interface ServiceRequestCreateRequest {
  patientId: string
  type: ServiceRequestType
  priority?: ServiceRequestPriority
  title: string
  description?: string
}

/** Payload to apply a controlled status transition (optimistic-locked). */
export interface StatusChangeRequest {
  targetStatus: ServiceRequestStatus
  expectedVersion: number
  reason?: string
}

/** One comment on a request (mirrors RequestCommentDto). */
export interface RequestComment {
  id: string
  authorUserId: string
  body: string
  createdAt: string
}

/** Payload to add a comment. Tenant, request, and author are set on the server. */
export interface AddCommentRequest {
  body: string
}
