import type { ClaimStatus, LineOutcome } from '../api/types'

type ChipColor = 'default' | 'primary' | 'secondary' | 'success' | 'error' | 'warning' | 'info'

/** Colour is a supplementary cue; the label text always communicates the status. */
export function claimStatusColor(status: ClaimStatus): ChipColor {
  switch (status) {
    case 'ACCEPTED':
    case 'ADJUDICATED':
      return 'success'
    case 'REJECTED':
    case 'CANCELLED':
      return 'error'
    case 'SUBMITTED':
      return 'info'
    default:
      return 'default'
  }
}

/** Colour for a per-line adjudication outcome. */
export function lineOutcomeColor(outcome: LineOutcome): ChipColor {
  switch (outcome) {
    case 'COVERED':
      return 'success'
    case 'AUTH_REQUIRED':
      return 'warning'
    default:
      return 'default'
  }
}
