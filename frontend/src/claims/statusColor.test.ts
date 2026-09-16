import { describe, expect, it } from 'vitest'
import { lineOutcomeColor } from './statusColor'

describe('lineOutcomeColor', () => {
  it('maps each line outcome to a chip colour', () => {
    expect(lineOutcomeColor('COVERED')).toBe('success')
    expect(lineOutcomeColor('AUTH_REQUIRED')).toBe('warning')
    expect(lineOutcomeColor('OUT_OF_NETWORK')).toBe('error')
    expect(lineOutcomeColor('NOT_COVERED')).toBe('default')
  })
})
