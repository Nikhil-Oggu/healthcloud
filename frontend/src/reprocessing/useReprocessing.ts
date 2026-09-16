import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CreateReprocessingBatchRequest } from '../api/types'

export const REPROCESSING_BATCHES_QUERY_KEY = ['reprocessing-batches'] as const
export const reprocessingBatchKey = (id: string) => ['reprocessing-batches', id] as const

/** The current tenant's reprocessing batches (newest first), a reviewer/admin work queue. */
export function useReprocessingBatches() {
  return useQuery({
    queryKey: REPROCESSING_BATCHES_QUERY_KEY,
    queryFn: () => api.listReprocessingBatches(),
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
