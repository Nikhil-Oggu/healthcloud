import type { AppealStatus } from '../api/types'

type ChipColor = 'default' | 'primary' | 'secondary' | 'success' | 'error' | 'warning' | 'info'

/** Colour is a supplementary cue; the label text always communicates the status. */
export function appealStatusColor(status: AppealStatus): ChipColor {
  switch (status) {
    case 'OVERTURNED':
      return 'success' // the appeal was granted — the claim decision is reversed
    case 'UPHELD':
      return 'default' // the original claim decision stood
    case 'WITHDRAWN':
      return 'default'
    case 'SUBMITTED':
      return 'info'
    default:
      return 'default'
  }
}
