import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { PriorAuthStatusChange } from '../api/types'

export const PRIOR_AUTHS_QUERY_KEY = ['prior-authorizations'] as const
export const priorAuthKey = (id: string) => ['prior-authorizations', id] as const
export const priorAuthHistoryKey = (id: string) => ['prior-authorizations', id, 'history'] as const

/** The current tenant's prior authorizations (backend scopes: provider → assigned; reviewer/admin → all). */
export function usePriorAuthorizations() {
  return useQuery({ queryKey: PRIOR_AUTHS_QUERY_KEY, queryFn: () => api.listPriorAuthorizations() })
}

export function usePriorAuthorization(id: string) {
  return useQuery({ queryKey: priorAuthKey(id), queryFn: () => api.getPriorAuthorization(id) })
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
