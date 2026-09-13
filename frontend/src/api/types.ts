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
