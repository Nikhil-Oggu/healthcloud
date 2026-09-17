import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CreateReferralRequest, ReferralStatus, ReferralStatusChange } from '../api/types'

export const REFERRALS_QUERY_KEY = ['referrals'] as const
export const referralKey = (id: string) => ['referrals', id] as const
export const referralHistoryKey = (id: string) => ['referrals', id, 'history'] as const

/** The parameters that drive a page of the referral work queue (§Phase 9). */
export interface ReferralsPageParams {
  patientId?: string
  status?: ReferralStatus
  page: number
  size: number
  sort?: string
}

/**
 * A page of the referral work queue for the given params (§Phase 9). The query key carries the params so a
 * page/sort/filter change refetches; it stays prefixed with {@link REFERRALS_QUERY_KEY} so a create/decision
 * still invalidates it. `keepPreviousData` keeps the current rows on screen while the next page loads.
 */
export function useReferrals(params: ReferralsPageParams) {
  return useQuery({
    queryKey: [...REFERRALS_QUERY_KEY, 'page', params],
    queryFn: () => api.listReferrals(params),
    placeholderData: keepPreviousData,
  })
}

export function useReferral(id: string) {
  return useQuery({ queryKey: referralKey(id), queryFn: () => api.getReferral(id) })
}

/** Request a referral (REQUESTED), then refresh the list. */
export function useCreateReferral() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateReferralRequest) => api.createReferral(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: REFERRALS_QUERY_KEY }),
  })
}

export function useReferralHistory(id: string) {
  return useQuery({ queryKey: referralHistoryKey(id), queryFn: () => api.getReferralHistory(id) })
}

/** Apply a referral transition (approve/deny/cancel), then refresh the referral, its history, and the list. */
export function useChangeReferralStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ReferralStatusChange) => api.changeReferralStatus(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: referralKey(id) })
      queryClient.invalidateQueries({ queryKey: referralHistoryKey(id) })
      queryClient.invalidateQueries({ queryKey: REFERRALS_QUERY_KEY })
    },
  })
}
