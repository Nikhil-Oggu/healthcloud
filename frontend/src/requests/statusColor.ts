import type { ServiceRequestStatus } from '../api/types'

type ChipColor = 'default' | 'primary' | 'secondary' | 'success' | 'error' | 'warning' | 'info'

/** Non-color-only status communication still shows the label text; color is a supplementary cue. */
export function statusColor(status: ServiceRequestStatus): ChipColor {
  switch (status) {
    case 'APPROVED':
    case 'CLOSED':
      return 'success'
    case 'REJECTED':
    case 'CANCELLED':
      return 'error'
    case 'NEEDS_INFORMATION':
      return 'warning'
    case 'UNDER_REVIEW':
    case 'TRIAGED':
    case 'ASSIGNED':
      return 'info'
    default:
      return 'default'
  }
}
