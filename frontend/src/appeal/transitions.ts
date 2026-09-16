import type { AppealStatus } from '../api/types'

/**
 * A CLIENT-SIDE MIRROR of the backend appeal state machine (see AppealTransitions.java), used only to decide
 * which action buttons to show. The backend remains the sole enforcer — every transition is re-validated
 * server-side, so this staying in sync is a UX nicety, not a security control.
 *
 * Uphold/overturn are the CLAIMS_REVIEWER's decision (appeals are a claims-review function); withdraw is the
 * submitter's. A reason is required on EVERY transition (unlike referral/prior-auth) — an appeal outcome or
 * withdrawal always needs a rationale.
 */
const ALLOWED: Record<AppealStatus, AppealStatus[]> = {
  SUBMITTED: ['UPHELD', 'OVERTURNED', 'WITHDRAWN'],
  UPHELD: [],
  OVERTURNED: [],
  WITHDRAWN: [],
}

/** Roles allowed to perform a move to `to` (mirrors the backend rules). */
function isRoleAllowed(to: AppealStatus, roles: string[]): boolean {
  const has = (...rs: string[]) => rs.some((r) => roles.includes(r))
  switch (to) {
    case 'UPHELD':
    case 'OVERTURNED':
      return has('CLAIMS_REVIEWER', 'ORG_ADMIN')
    case 'WITHDRAWN':
      return has('PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN')
    default:
      return false
  }
}

/** A reason is mandatory on every appeal transition. */
export function reasonRequired(_to: AppealStatus): boolean {
  return true
}

/** The transition actions the given roles may perform from `from` (for showing buttons). */
export function allowedActions(from: AppealStatus, roles: string[]): AppealStatus[] {
  return ALLOWED[from].filter((to) => isRoleAllowed(to, roles))
}

/** Human label for a transition button. */
export function actionLabel(to: AppealStatus): string {
  const labels: Record<AppealStatus, string> = {
    SUBMITTED: 'Submit',
    UPHELD: 'Uphold',
    OVERTURNED: 'Overturn',
    WITHDRAWN: 'Withdraw',
  }
  return labels[to]
}
