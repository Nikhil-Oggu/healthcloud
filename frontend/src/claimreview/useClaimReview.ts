import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ClaimReviewStatusChange, CreateClaimReviewRequest } from '../api/types'

export const CLAIM_REVIEWS_QUERY_KEY = ['claim-reviews'] as const
export const claimReviewKey = (id: string) => ['claim-reviews', id] as const
export const claimReviewHistoryKey = (id: string) => ['claim-reviews', id, 'history'] as const

/** The current tenant's reviews (backend scopes: provider → assigned; reviewer/coordinator/admin → all). */
export function useClaimReviews() {
  return useQuery({ queryKey: CLAIM_REVIEWS_QUERY_KEY, queryFn: () => api.listClaimReviews() })
}

export function useClaimReview(id: string) {
  return useQuery({ queryKey: claimReviewKey(id), queryFn: () => api.getClaimReview(id) })
}

/** Open a review (OPEN), then refresh the list. */
export function useCreateClaimReview() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateClaimReviewRequest) => api.createClaimReview(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: CLAIM_REVIEWS_QUERY_KEY }),
  })
}

export function useClaimReviewHistory(id: string) {
  return useQuery({ queryKey: claimReviewHistoryKey(id), queryFn: () => api.getClaimReviewHistory(id) })
}

/** Apply a review transition (resolve/cancel), then refresh the review, its history, and the list. */
export function useChangeClaimReviewStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ClaimReviewStatusChange) => api.changeClaimReviewStatus(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: claimReviewKey(id) })
      queryClient.invalidateQueries({ queryKey: claimReviewHistoryKey(id) })
      queryClient.invalidateQueries({ queryKey: CLAIM_REVIEWS_QUERY_KEY })
    },
  })
}
