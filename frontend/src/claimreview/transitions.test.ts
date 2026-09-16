import { describe, expect, it } from 'vitest'
import { actionLabel, allowedActions, reasonRequired } from './transitions'

describe('claim review transitions (client mirror)', () => {
  it('a reviewer can resolve or cancel an open review', () => {
    expect(allowedActions('OPEN', ['CLAIMS_REVIEWER']).sort()).toEqual(['CANCELLED', 'RESOLVED'])
  })

  it('a coordinator can cancel but not resolve', () => {
    expect(allowedActions('OPEN', ['CARE_COORDINATOR'])).toEqual(['CANCELLED'])
  })

  it('a provider (neither opener nor resolver) can do nothing', () => {
    expect(allowedActions('OPEN', ['PROVIDER'])).toEqual([])
  })

  it('an admin can do both; terminal states offer no actions', () => {
    expect(allowedActions('OPEN', ['ORG_ADMIN']).sort()).toEqual(['CANCELLED', 'RESOLVED'])
    expect(allowedActions('RESOLVED', ['ORG_ADMIN'])).toEqual([])
    expect(allowedActions('CANCELLED', ['CLAIMS_REVIEWER'])).toEqual([])
  })

  it('a reason is required on every transition', () => {
    expect(reasonRequired('RESOLVED')).toBe(true)
    expect(reasonRequired('CANCELLED')).toBe(true)
    expect(actionLabel('RESOLVED')).toBe('Resolve')
    expect(actionLabel('CANCELLED')).toBe('Cancel')
  })
})
