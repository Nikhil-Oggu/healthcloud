import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

export const DEAD_LETTER_EVENTS_QUERY_KEY = ['dead-letter-events'] as const

/** The parameters that drive a page of the dead-letter queue (§Phase 9). */
export interface DeadLetterPageParams {
  /** Free-text search — a case-insensitive match on the event id or message key (§Phase 9 slice 8). */
  q?: string
  page: number
  size: number
  sort?: string
}

/**
 * A page of the tenant's dead-lettered messages (§Phase 9) — an ORG_ADMIN operational read. The query key carries
 * the params so a page/sort change refetches; still prefixed so a replay invalidates it. `keepPreviousData` keeps
 * the current rows on screen while the next page loads.
 */
export function useDeadLetterEvents(params: DeadLetterPageParams) {
  return useQuery({
    queryKey: [...DEAD_LETTER_EVENTS_QUERY_KEY, 'page', params],
    queryFn: () => api.listDeadLetterEvents(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * Replay a dead-letter record (ORG_ADMIN): re-drive its message onto the source topic. On success we invalidate the
 * list so the row reloads showing its replay stamp (and drops its Replay button).
 */
export function useReplayDeadLetter() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.replayDeadLetterEvent(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: DEAD_LETTER_EVENTS_QUERY_KEY }),
  })
}
