import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type {
  CreatePriorAuthorizationRequest,
  PriorAuthorizationStatus,
  PriorAuthStatusChange,
} from '../api/types'

export const PRIOR_AUTHS_QUERY_KEY = ['prior-authorizations'] as const
export const priorAuthKey = (id: string) => ['prior-authorizations', id] as const
export const priorAuthHistoryKey = (id: string) => ['prior-authorizations', id, 'history'] as const

/** The parameters that drive a page of the prior-auth work queue (§Phase 9). */
export interface PriorAuthsPageParams {
  patientId?: string
  status?: PriorAuthorizationStatus
  page: number
  size: number
  sort?: string
}

/**
 * A page of the prior-auth work queue for the given params (§Phase 9). The query key carries the params so a
 * page/sort/filter change refetches; it stays prefixed with {@link PRIOR_AUTHS_QUERY_KEY} so a create/decision
 * still invalidates it. `keepPreviousData` keeps the current rows on screen while the next page loads (no flash).
 * The backend scopes to the caller (provider → assigned; reviewer/admin → all).
 */
export function usePriorAuthorizations(params: PriorAuthsPageParams) {
  return useQuery({
    queryKey: [...PRIOR_AUTHS_QUERY_KEY, 'page', params],
    queryFn: () => api.listPriorAuthorizations(params),
    placeholderData: keepPreviousData,
  })
}

export function usePriorAuthorization(id: string) {
  return useQuery({ queryKey: priorAuthKey(id), queryFn: () => api.getPriorAuthorization(id) })
}

/** Request a prior authorization (REQUESTED), then refresh the list. */
export function useCreatePriorAuthorization() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreatePriorAuthorizationRequest) => api.createPriorAuthorization(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRIOR_AUTHS_QUERY_KEY }),
  })
}

export function usePriorAuthHistory(id: string) {
  return useQuery({ queryKey: priorAuthHistoryKey(id), queryFn: () => api.getPriorAuthHistory(id) })
}

/** Apply a prior-auth transition (approve/deny/cancel), then refresh the auth, its history, and the list. */
export function useChangePriorAuthStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: PriorAuthStatusChange) => api.changePriorAuthStatus(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: priorAuthKey(id) })
      queryClient.invalidateQueries({ queryKey: priorAuthHistoryKey(id) })
      queryClient.invalidateQueries({ queryKey: PRIOR_AUTHS_QUERY_KEY })
    },
  })
}
