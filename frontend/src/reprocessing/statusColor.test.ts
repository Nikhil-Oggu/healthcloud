import { describe, expect, it } from 'vitest'
import { itemOutcomeColor, reprocessingStatusColor } from './statusColor'

describe('reprocessing status colours', () => {
  it('maps batch status to a chip colour', () => {
    expect(reprocessingStatusColor('COMPLETED')).toBe('success')
    expect(reprocessingStatusColor('COMPLETED_WITH_ERRORS')).toBe('warning')
    expect(reprocessingStatusColor('RUNNING')).toBe('info')
  })

  it('maps item outcome to a chip colour', () => {
    expect(itemOutcomeColor('SUCCEEDED')).toBe('success')
    expect(itemOutcomeColor('FAILED')).toBe('error')
  })
})
