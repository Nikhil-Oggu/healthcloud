import { describe, expect, it } from 'vitest'
import { actionLabel, allowedActions, reasonRequired } from './transitions'

describe('appeal transitions (client mirror)', () => {
  it('a reviewer can uphold or overturn a submitted appeal but not withdraw', () => {
    expect(allowedActions('SUBMITTED', ['CLAIMS_REVIEWER']).sort()).toEqual(['OVERTURNED', 'UPHELD'])
  })

  it('a submitter can withdraw but not decide', () => {
    expect(allowedActions('SUBMITTED', ['CARE_COORDINATOR'])).toEqual(['WITHDRAWN'])
    expect(allowedActions('SUBMITTED', ['PROVIDER'])).toEqual(['WITHDRAWN'])
  })

  it('an admin can do everything; terminal states offer no actions', () => {
    expect(allowedActions('SUBMITTED', ['ORG_ADMIN']).sort()).toEqual(
      ['OVERTURNED', 'UPHELD', 'WITHDRAWN'].sort(),
    )
    expect(allowedActions('UPHELD', ['ORG_ADMIN'])).toEqual([])
    expect(allowedActions('OVERTURNED', ['CLAIMS_REVIEWER'])).toEqual([])
  })

  it('a reason is required on every transition', () => {
    expect(reasonRequired('UPHELD')).toBe(true)
    expect(reasonRequired('OVERTURNED')).toBe(true)
    expect(reasonRequired('WITHDRAWN')).toBe(true)
    expect(actionLabel('OVERTURNED')).toBe('Overturn')
  })
})
