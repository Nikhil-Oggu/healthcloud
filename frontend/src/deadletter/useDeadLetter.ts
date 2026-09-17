import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

export const DEAD_LETTER_EVENTS_QUERY_KEY = ['dead-letter-events'] as const

/** The tenant's dead-lettered messages, newest first — an ORG_ADMIN operational read. */
export function useDeadLetterEvents() {
  return useQuery({ queryKey: DEAD_LETTER_EVENTS_QUERY_KEY, queryFn: () => api.listDeadLetterEvents() })
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
