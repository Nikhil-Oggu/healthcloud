import type { ServiceRequestStatus } from '../api/types'

/**
 * A CLIENT-SIDE MIRROR of the backend §14.6 state machine (see RequestTransitions.java), used only to
 * decide which action buttons to show. The backend remains the sole enforcer — every transition is
 * re-validated server-side, so this staying perfectly in sync is a UX nicety, not a security control.
 */
const ALLOWED: Record<ServiceRequestStatus, ServiceRequestStatus[]> = {
  DRAFT: ['SUBMITTED', 'CANCELLED'],
  SUBMITTED: ['TRIAGED', 'CANCELLED'],
  TRIAGED: ['ASSIGNED', 'CANCELLED'],
  ASSIGNED: ['UNDER_REVIEW', 'CANCELLED'],
  UNDER_REVIEW: ['NEEDS_INFORMATION', 'APPROVED', 'REJECTED'],
  NEEDS_INFORMATION: ['UNDER_REVIEW', 'CANCELLED'],
  APPROVED: ['CLOSED'],
  REJECTED: ['CLOSED'],
  CANCELLED: [],
  CLOSED: [],
}

/** Roles allowed to perform a move to `to` from `from` (mirrors the backend rules). */
function isRoleAllowed(from: ServiceRequestStatus, to: ServiceRequestStatus, roles: string[]): boolean {
  const has = (...rs: string[]) => rs.some((r) => roles.includes(r))
  if (to === 'CANCELLED') {
    const patientCancel =
      roles.includes('PATIENT') && ['DRAFT', 'SUBMITTED', 'NEEDS_INFORMATION'].includes(from)
    const staffCancel =
      has('CARE_COORDINATOR', 'ORG_ADMIN') &&
      ['SUBMITTED', 'TRIAGED', 'ASSIGNED', 'NEEDS_INFORMATION'].includes(from)
    return patientCancel || staffCancel
  }
  switch (to) {
    case 'SUBMITTED':
      return has('PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN')
    case 'TRIAGED':
    case 'ASSIGNED':
      return has('CARE_COORDINATOR', 'ORG_ADMIN')
    case 'UNDER_REVIEW':
    case 'NEEDS_INFORMATION':
      return has('PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN')
    case 'APPROVED':
    case 'REJECTED':
    case 'CLOSED':
      return has('CARE_COORDINATOR', 'ORG_ADMIN')
    default:
      return false
  }
}

/** A reason is mandatory when cancelling or rejecting. */
export function reasonRequired(to: ServiceRequestStatus): boolean {
  return to === 'CANCELLED' || to === 'REJECTED'
}

/**
 * The next statuses the given roles may move `from` to (for showing action buttons). ASSIGNED is
 * excluded: a request reaches ASSIGNED only by assigning a user (the Assignment card / backend
 * PUT .../assignment), never a bare status change — so it never appears as a status button.
 */
export function allowedActions(from: ServiceRequestStatus, roles: string[]): ServiceRequestStatus[] {
  return ALLOWED[from].filter((to) => to !== 'ASSIGNED' && isRoleAllowed(from, to, roles))
}

/** Human label for a transition button. */
export function actionLabel(to: ServiceRequestStatus): string {
  const labels: Record<ServiceRequestStatus, string> = {
    SUBMITTED: 'Submit',
    TRIAGED: 'Triage',
    ASSIGNED: 'Assign',
    UNDER_REVIEW: 'Start review',
    NEEDS_INFORMATION: 'Request info',
    APPROVED: 'Approve',
    REJECTED: 'Reject',
    CANCELLED: 'Cancel',
    CLOSED: 'Close',
    DRAFT: 'Draft',
  }
  return labels[to]
}
