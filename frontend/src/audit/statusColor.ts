import type { AuditAction, AuditOutcome } from '../api/types'

type ChipColor = 'default' | 'primary' | 'secondary' | 'success' | 'error' | 'warning' | 'info'

/** Colour is a supplementary cue; the label text always communicates the value. */
export function auditOutcomeColor(outcome: AuditOutcome): ChipColor {
  return outcome === 'SUCCESS' ? 'success' : 'error'
}

/** A muted, per-action colour so a scan of the log groups by kind of event. */
export function auditActionColor(action: AuditAction): ChipColor {
  switch (action) {
    case 'CLAIM_ADJUDICATED':
      return 'info'
    case 'CONSENT_REVOKED':
      return 'warning'
    default:
      return 'default'
  }
}
