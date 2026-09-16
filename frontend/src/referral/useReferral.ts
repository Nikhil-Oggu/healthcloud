import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CreateReferralRequest, ReferralStatusChange } from '../api/types'

export const REFERRALS_QUERY_KEY = ['referrals'] as const
export const referralKey = (id: string) => ['referrals', id] as const
export const referralHistoryKey = (id: string) => ['referrals', id, 'history'] as const

/** The current tenant's referrals (backend scopes: provider → assigned; coordinator/admin/reviewer → all). */
export function useReferrals() {
  return useQuery({ queryKey: REFERRALS_QUERY_KEY, queryFn: () => api.listReferrals() })
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
