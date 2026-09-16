import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AppealStatusChange, CreateAppealRequest } from '../api/types'

export const APPEALS_QUERY_KEY = ['appeals'] as const
export const appealKey = (id: string) => ['appeals', id] as const
export const appealHistoryKey = (id: string) => ['appeals', id, 'history'] as const

/** The current tenant's appeals (backend scopes: provider → assigned; reviewer/coordinator/admin → all). */
export function useAppeals() {
  return useQuery({ queryKey: APPEALS_QUERY_KEY, queryFn: () => api.listAppeals() })
}

export function useAppeal(id: string) {
  return useQuery({ queryKey: appealKey(id), queryFn: () => api.getAppeal(id) })
}

/** Submit an appeal (SUBMITTED), then refresh the list. */
export function useCreateAppeal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateAppealRequest) => api.createAppeal(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: APPEALS_QUERY_KEY }),
  })
}

export function useAppealHistory(id: string) {
  return useQuery({ queryKey: appealHistoryKey(id), queryFn: () => api.getAppealHistory(id) })
}

/** Apply an appeal transition (uphold/overturn/withdraw), then refresh the appeal, its history, and the list. */
export function useChangeAppealStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AppealStatusChange) => api.changeAppealStatus(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: appealKey(id) })
      queryClient.invalidateQueries({ queryKey: appealHistoryKey(id) })
      queryClient.invalidateQueries({ queryKey: APPEALS_QUERY_KEY })
    },
  })
}
