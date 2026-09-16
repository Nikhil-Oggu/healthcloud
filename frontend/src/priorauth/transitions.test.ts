import { describe, expect, it } from 'vitest'
import { allowedActions, reasonRequired } from './transitions'

describe('prior-auth transitions (client mirror)', () => {
  it('a reviewer may approve or deny a REQUESTED authorization', () => {
    expect(allowedActions('REQUESTED', ['CLAIMS_REVIEWER']).sort()).toEqual(['APPROVED', 'DENIED'])
    expect(allowedActions('REQUESTED', ['ORG_ADMIN'])).toContain('APPROVED')
  })

  it('a requester (coordinator/provider) may cancel but not decide', () => {
    expect(allowedActions('REQUESTED', ['CARE_COORDINATOR'])).toEqual(['CANCELLED'])
    expect(allowedActions('REQUESTED', ['PROVIDER'])).toEqual(['CANCELLED'])
  })

  it('terminal states offer no actions', () => {
    expect(allowedActions('APPROVED', ['ORG_ADMIN'])).toEqual([])
    expect(allowedActions('DENIED', ['ORG_ADMIN'])).toEqual([])
    expect(allowedActions('CANCELLED', ['ORG_ADMIN'])).toEqual([])
  })

  it('a reason is required only to deny or cancel', () => {
    expect(reasonRequired('DENIED')).toBe(true)
    expect(reasonRequired('CANCELLED')).toBe(true)
    expect(reasonRequired('APPROVED')).toBe(false)
  })
})
