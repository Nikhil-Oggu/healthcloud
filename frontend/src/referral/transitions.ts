import type { ReferralStatus } from '../api/types'

/**
 * A CLIENT-SIDE MIRROR of the backend referral state machine (see ReferralTransitions.java), used only to decide
 * which action buttons to show. The backend remains the sole enforcer — every transition is re-validated
 * server-side, so this staying in sync is a UX nicety, not a security control.
 *
 * Note the decision roles differ from prior auth: a referral is approved/denied by a CARE_COORDINATOR/ORG_ADMIN
 * (referral routing is care coordination's call), not the CLAIMS_REVIEWER.
 */
const ALLOWED: Record<ReferralStatus, ReferralStatus[]> = {
  REQUESTED: ['APPROVED', 'DENIED', 'CANCELLED'],
  APPROVED: [],
  DENIED: [],
  CANCELLED: [],
}

/** Roles allowed to perform a move to `to` (mirrors the backend rules). */
function isRoleAllowed(to: ReferralStatus, roles: string[]): boolean {
  const has = (...rs: string[]) => rs.some((r) => roles.includes(r))
  switch (to) {
    case 'APPROVED':
    case 'DENIED':
      return has('CARE_COORDINATOR', 'ORG_ADMIN')
    case 'CANCELLED':
      return has('PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN')
    default:
      return false
  }
}

/** A reason is mandatory when denying or cancelling. */
export function reasonRequired(to: ReferralStatus): boolean {
  return to === 'DENIED' || to === 'CANCELLED'
}

/** The transition actions the given roles may perform from `from` (for showing buttons). */
export function allowedActions(from: ReferralStatus, roles: string[]): ReferralStatus[] {
  return ALLOWED[from].filter((to) => isRoleAllowed(to, roles))
}

/** Human label for a transition button. */
export function actionLabel(to: ReferralStatus): string {
  const labels: Record<ReferralStatus, string> = {
    REQUESTED: 'Request',
    APPROVED: 'Approve',
    DENIED: 'Deny',
    CANCELLED: 'Cancel',
  }
  return labels[to]
}
