import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AppealStatus, AppealStatusChange, CreateAppealRequest } from '../api/types'

export const APPEALS_QUERY_KEY = ['appeals'] as const
export const appealKey = (id: string) => ['appeals', id] as const
export const appealHistoryKey = (id: string) => ['appeals', id, 'history'] as const

/** The parameters that drive a page of the appeal work queue (§Phase 9). */
export interface AppealsPageParams {
  claimId?: string
  status?: AppealStatus
  page: number
  size: number
  sort?: string
}

/**
 * A page of the appeal work queue for the given params (§Phase 9). The query key carries the params so a
 * page/sort/filter change refetches; it stays prefixed with {@link APPEALS_QUERY_KEY} so a create/decision
 * still invalidates it. `keepPreviousData` keeps the current rows on screen while the next page loads.
 */
export function useAppeals(params: AppealsPageParams) {
  return useQuery({
    queryKey: [...APPEALS_QUERY_KEY, 'page', params],
    queryFn: () => api.listAppeals(params),
    placeholderData: keepPreviousData,
  })
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
