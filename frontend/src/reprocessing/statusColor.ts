import type { ReprocessingBatchStatus, ReprocessingItemOutcome } from '../api/types'

type ChipColor = 'default' | 'primary' | 'secondary' | 'success' | 'error' | 'warning' | 'info'

/** Colour is a supplementary cue; the label text always communicates the status. */
export function reprocessingStatusColor(status: ReprocessingBatchStatus): ChipColor {
  switch (status) {
    case 'COMPLETED':
      return 'success'
    case 'COMPLETED_WITH_ERRORS':
      return 'warning'
    case 'RUNNING':
      return 'info'
    default:
      return 'default'
  }
}

/** Per-claim item outcome colour (SUCCEEDED → success, FAILED → error). */
export function itemOutcomeColor(outcome: ReprocessingItemOutcome): ChipColor {
  return outcome === 'SUCCEEDED' ? 'success' : 'error'
}
