import type { ClaimReviewStatus } from '../api/types'

/**
 * A CLIENT-SIDE MIRROR of the backend claim-review state machine (see ClaimReviewTransitions.java), used only to
 * decide which action buttons to show. The backend remains the sole enforcer — every transition is re-validated
 * server-side, so this staying in sync is a UX nicety, not a security control.
 *
 * Resolve is the CLAIMS_REVIEWER's disposition; cancel is an opener's (coordinator/reviewer/admin). A reason is
 * required on EVERY transition — the resolution conclusion, or a cancellation rationale.
 */
const ALLOWED: Record<ClaimReviewStatus, ClaimReviewStatus[]> = {
  OPEN: ['RESOLVED', 'CANCELLED'],
  RESOLVED: [],
  CANCELLED: [],
}

/** Roles allowed to perform a move to `to` (mirrors the backend rules). */
function isRoleAllowed(to: ClaimReviewStatus, roles: string[]): boolean {
  const has = (...rs: string[]) => rs.some((r) => roles.includes(r))
  switch (to) {
    case 'RESOLVED':
      return has('CLAIMS_REVIEWER', 'ORG_ADMIN')
    case 'CANCELLED':
      return has('CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN')
    default:
      return false
  }
}

/** A reason is mandatory on every review transition. */
export function reasonRequired(_to: ClaimReviewStatus): boolean {
  return true
}

/** The transition actions the given roles may perform from `from` (for showing buttons). */
export function allowedActions(from: ClaimReviewStatus, roles: string[]): ClaimReviewStatus[] {
  return ALLOWED[from].filter((to) => isRoleAllowed(to, roles))
}

/** Human label for a transition button. */
export function actionLabel(to: ClaimReviewStatus): string {
  const labels: Record<ClaimReviewStatus, string> = {
    OPEN: 'Open',
    RESOLVED: 'Resolve',
    CANCELLED: 'Cancel',
  }
  return labels[to]
}
