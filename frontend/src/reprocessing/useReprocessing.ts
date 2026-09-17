import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CreateReprocessingBatchRequest, ReprocessingBatchStatus } from '../api/types'

export const REPROCESSING_BATCHES_QUERY_KEY = ['reprocessing-batches'] as const
export const reprocessingBatchKey = (id: string) => ['reprocessing-batches', id] as const

/** The parameters that drive a page of the reprocessing work queue (§Phase 9). */
export interface ReprocessingPageParams {
  status?: ReprocessingBatchStatus
  /** Free-text search — a case-insensitive "contains" match on the batch number (§Phase 9 slice 7). */
  q?: string
  page: number
  size: number
  sort?: string
}

/**
 * A page of the reprocessing work queue for the given params (§Phase 9). The query key carries the params so a
 * page/sort/filter change refetches; it stays prefixed with {@link REPROCESSING_BATCHES_QUERY_KEY} so a run
 * still invalidates it. `keepPreviousData` keeps the current rows on screen while the next page loads.
 */
export function useReprocessingBatches(params: ReprocessingPageParams) {
  return useQuery({
    queryKey: [...REPROCESSING_BATCHES_QUERY_KEY, 'page', params],
    queryFn: () => api.listReprocessingBatches(params),
    placeholderData: keepPreviousData,
  })
}

export function useReprocessingBatch(id: string) {
  return useQuery({ queryKey: reprocessingBatchKey(id), queryFn: () => api.getReprocessingBatch(id) })
}

/** Run a batch for a coverage plan (synchronous on the backend), then refresh the list. */
export function useRunReprocessingBatch() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateReprocessingBatchRequest) => api.runReprocessingBatch(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: REPROCESSING_BATCHES_QUERY_KEY }),
  })
}
