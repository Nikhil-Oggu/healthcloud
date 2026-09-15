import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ClaimStatusChange } from '../api/types'

export const CLAIMS_QUERY_KEY = ['claims'] as const
export const claimKey = (id: string) => ['claims', id] as const
export const claimHistoryKey = (id: string) => ['claims', id, 'history'] as const
export const adjudicationKey = (id: string) => ['claims', id, 'adjudication'] as const

/** The current tenant's claims (backend scopes to the caller: provider → assigned; reviewer/admin → all). */
export function useClaims() {
  return useQuery({ queryKey: CLAIMS_QUERY_KEY, queryFn: () => api.listClaims() })
}

export function useClaim(id: string) {
  return useQuery({ queryKey: claimKey(id), queryFn: () => api.getClaim(id) })
}

export function useClaimHistory(id: string) {
  return useQuery({ queryKey: claimHistoryKey(id), queryFn: () => api.getClaimHistory(id) })
}

/**
 * The claim's adjudication, fetched only once it exists (status ADJUDICATED) — a not-yet-adjudicated claim
 * has no adjudication and would 404, so the query is disabled until then.
 */
export function useAdjudication(id: string, enabled: boolean) {
  return useQuery({ queryKey: adjudicationKey(id), queryFn: () => api.getAdjudication(id), enabled })
}

/** Apply a claim status transition, then refresh the claim, its history, and the list. */
export function useChangeClaimStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ClaimStatusChange) => api.changeClaimStatus(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: claimKey(id) })
      queryClient.invalidateQueries({ queryKey: claimHistoryKey(id) })
      queryClient.invalidateQueries({ queryKey: CLAIMS_QUERY_KEY })
    },
  })
}

/** Run the adjudication engine, then refresh the claim, its history, the adjudication, and the list. */
export function useAdjudicate(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.adjudicateClaim(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: claimKey(id) })
      queryClient.invalidateQueries({ queryKey: claimHistoryKey(id) })
      queryClient.invalidateQueries({ queryKey: adjudicationKey(id) })
      queryClient.invalidateQueries({ queryKey: CLAIMS_QUERY_KEY })
    },
  })
}
