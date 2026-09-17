import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ClaimReviewStatus, ClaimReviewStatusChange, CreateClaimReviewRequest } from '../api/types'

export const CLAIM_REVIEWS_QUERY_KEY = ['claim-reviews'] as const
export const claimReviewKey = (id: string) => ['claim-reviews', id] as const
export const claimReviewHistoryKey = (id: string) => ['claim-reviews', id, 'history'] as const

/** The parameters that drive a page of the manual-review work queue (§Phase 9). */
export interface ClaimReviewsPageParams {
  claimId?: string
  status?: ClaimReviewStatus
  page: number
  size: number
  sort?: string
}

/**
 * A page of the manual-review work queue for the given params (§Phase 9). The query key carries the params so a
 * page/sort/filter change refetches; it stays prefixed with {@link CLAIM_REVIEWS_QUERY_KEY} so a create/decision
 * still invalidates it. `keepPreviousData` keeps the current rows on screen while the next page loads.
 */
export function useClaimReviews(params: ClaimReviewsPageParams) {
  return useQuery({
    queryKey: [...CLAIM_REVIEWS_QUERY_KEY, 'page', params],
    queryFn: () => api.listClaimReviews(params),
    placeholderData: keepPreviousData,
  })
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
