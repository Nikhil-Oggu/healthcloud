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
