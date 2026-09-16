import type { PriorAuthorizationStatus } from '../api/types'

type ChipColor = 'default' | 'primary' | 'secondary' | 'success' | 'error' | 'warning' | 'info'

/** Colour is a supplementary cue; the label text always communicates the status. */
export function priorAuthStatusColor(status: PriorAuthorizationStatus): ChipColor {
  switch (status) {
    case 'APPROVED':
      return 'success'
    case 'DENIED':
      return 'error'
    case 'CANCELLED':
      return 'default'
    case 'REQUESTED':
      return 'info'
    default:
      return 'default'
  }
}
