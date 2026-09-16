import { describe, expect, it } from 'vitest'
import { actionLabel, allowedActions, reasonRequired } from './transitions'

describe('referral transitions (client mirror)', () => {
  it('a coordinator can approve or deny a requested referral', () => {
    expect(allowedActions('REQUESTED', ['CARE_COORDINATOR']).sort()).toEqual(
      ['APPROVED', 'CANCELLED', 'DENIED'].sort(),
    )
  })

  it('a provider cannot decide but can cancel', () => {
    expect(allowedActions('REQUESTED', ['PROVIDER'])).toEqual(['CANCELLED'])
  })

  it('a claims reviewer cannot act on a referral (unlike prior auth)', () => {
    expect(allowedActions('REQUESTED', ['CLAIMS_REVIEWER'])).toEqual([])
  })

  it('terminal states offer no actions, and deny/cancel require a reason', () => {
    expect(allowedActions('APPROVED', ['ORG_ADMIN'])).toEqual([])
    expect(reasonRequired('DENIED')).toBe(true)
    expect(reasonRequired('CANCELLED')).toBe(true)
    expect(reasonRequired('APPROVED')).toBe(false)
    expect(actionLabel('APPROVED')).toBe('Approve')
  })
})
