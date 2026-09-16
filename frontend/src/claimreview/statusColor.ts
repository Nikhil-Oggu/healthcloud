import type { ClaimReviewStatus } from '../api/types'

type ChipColor = 'default' | 'primary' | 'secondary' | 'success' | 'error' | 'warning' | 'info'

/** Colour is a supplementary cue; the label text always communicates the status. */
export function claimReviewStatusColor(status: ClaimReviewStatus): ChipColor {
  switch (status) {
    case 'RESOLVED':
      return 'success'
    case 'CANCELLED':
      return 'default'
    case 'OPEN':
      return 'info'
    default:
      return 'default'
  }
}
