import type { ClaimStatus } from '../api/types'

/**
 * A CLIENT-SIDE MIRROR of the backend claim state machine (see ClaimTransitions.java), used only to decide
 * which action buttons to show. The backend remains the sole enforcer — every transition is re-validated
 * server-side, so this staying in sync is a UX nicety, not a security control.
 *
 * ADJUDICATED is deliberately absent from the button set: a claim reaches ADJUDICATED only via the dedicated
 * adjudication command (POST .../adjudicate), never a bare status change — exactly like ASSIGNED on a request.
 */
const ALLOWED: Record<ClaimStatus, ClaimStatus[]> = {
  DRAFT: ['SUBMITTED', 'CANCELLED'],
  SUBMITTED: ['ACCEPTED', 'REJECTED', 'CANCELLED'],
  ACCEPTED: [], // → ADJUDICATED is the engine command, not a status button
  REJECTED: [],
  ADJUDICATED: [],
  CANCELLED: [],
}

/** Roles allowed to perform a move to `to` (mirrors the backend rules). */
function isRoleAllowed(to: ClaimStatus, roles: string[]): boolean {
  const has = (...rs: string[]) => rs.some((r) => roles.includes(r))
  switch (to) {
    case 'SUBMITTED':
    case 'CANCELLED':
      return has('PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN')
    case 'ACCEPTED':
    case 'REJECTED':
      return has('CLAIMS_REVIEWER', 'ORG_ADMIN')
    default:
      return false
  }
}

/** A reason is mandatory when rejecting or cancelling. */
export function reasonRequired(to: ClaimStatus): boolean {
  return to === 'REJECTED' || to === 'CANCELLED'
}

/** The status-change actions the given roles may perform from `from` (for showing buttons). */
export function allowedActions(from: ClaimStatus, roles: string[]): ClaimStatus[] {
  return ALLOWED[from].filter((to) => isRoleAllowed(to, roles))
}

/** Whether the caller may run the adjudication engine on this claim (ACCEPTED → ADJUDICATED). */
export function canAdjudicate(status: ClaimStatus, roles: string[]): boolean {
  return status === 'ACCEPTED' && roles.some((r) => ['CLAIMS_REVIEWER', 'ORG_ADMIN'].includes(r))
}

/**
 * Whether the caller may re-adjudicate this claim (an already-ADJUDICATED claim → a new immutable version).
 * The backend runs the same command; each call appends a version and leaves the claim ADJUDICATED.
 */
export function canReadjudicate(status: ClaimStatus, roles: string[]): boolean {
  return status === 'ADJUDICATED' && roles.some((r) => ['CLAIMS_REVIEWER', 'ORG_ADMIN'].includes(r))
}

/** Human label for a transition button. */
export function actionLabel(to: ClaimStatus): string {
  const labels: Record<ClaimStatus, string> = {
    DRAFT: 'Draft',
    SUBMITTED: 'Submit',
    ACCEPTED: 'Accept',
    REJECTED: 'Reject',
    ADJUDICATED: 'Adjudicate',
    CANCELLED: 'Cancel',
  }
  return labels[to]
}
