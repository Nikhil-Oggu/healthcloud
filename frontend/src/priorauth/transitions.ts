import type { PriorAuthorizationStatus } from '../api/types'

/**
 * A CLIENT-SIDE MIRROR of the backend prior-auth state machine (see PriorAuthTransitions.java), used only to
 * decide which action buttons to show. The backend remains the sole enforcer — every transition is re-validated
 * server-side, so this staying in sync is a UX nicety, not a security control.
 */
const ALLOWED: Record<PriorAuthorizationStatus, PriorAuthorizationStatus[]> = {
  REQUESTED: ['APPROVED', 'DENIED', 'CANCELLED'],
  APPROVED: [],
  DENIED: [],
  CANCELLED: [],
}

/** Roles allowed to perform a move to `to` (mirrors the backend rules). */
function isRoleAllowed(to: PriorAuthorizationStatus, roles: string[]): boolean {
  const has = (...rs: string[]) => rs.some((r) => roles.includes(r))
  switch (to) {
    case 'APPROVED':
    case 'DENIED':
      return has('CLAIMS_REVIEWER', 'ORG_ADMIN')
    case 'CANCELLED':
      return has('PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN')
    default:
      return false
  }
}

/** A reason is mandatory when denying or cancelling. */
export function reasonRequired(to: PriorAuthorizationStatus): boolean {
  return to === 'DENIED' || to === 'CANCELLED'
}

/** The transition actions the given roles may perform from `from` (for showing buttons). */
export function allowedActions(from: PriorAuthorizationStatus, roles: string[]): PriorAuthorizationStatus[] {
  return ALLOWED[from].filter((to) => isRoleAllowed(to, roles))
}

/** Human label for a transition button. */
export function actionLabel(to: PriorAuthorizationStatus): string {
  const labels: Record<PriorAuthorizationStatus, string> = {
    REQUESTED: 'Request',
    APPROVED: 'Approve',
    DENIED: 'Deny',
    CANCELLED: 'Cancel',
  }
  return labels[to]
}
