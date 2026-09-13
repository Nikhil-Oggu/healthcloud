import { useQuery } from '@tanstack/react-query'
import { api, ApiClientError } from '../api/client'
import type { CurrentUser } from '../api/types'

/** Query key for the current-user ("who am I") query. */
export const ME_QUERY_KEY = ['me'] as const

/**
 * Loads the authenticated user from GET /api/v1/me. A 401/403 is a real answer ("not logged in" /
 * "no access"), not a transient failure, so those are never retried; other errors get one retry.
 */
export function useCurrentUser() {
  return useQuery<CurrentUser, ApiClientError>({
    queryKey: ME_QUERY_KEY,
    queryFn: api.me,
    retry: (failureCount, error) => {
      if (error instanceof ApiClientError && (error.status === 401 || error.status === 403)) {
        return false
      }
      return failureCount < 1
    },
    staleTime: 30_000,
  })
}
