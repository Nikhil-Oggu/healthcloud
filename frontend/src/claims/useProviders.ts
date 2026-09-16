import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

export const PROVIDERS_QUERY_KEY = ['providers'] as const

/**
 * The current tenant's active providers — the source for the rendering-provider picker on claim create (and for
 * resolving a claim's rendering-provider id → name on the detail page). The backend gates this to the
 * claim-create roles, so a viewer without those roles gets a 403; callers that only display a name should treat
 * an empty/failed result as "no name to show" rather than an error.
 */
export function useProviders() {
  return useQuery({ queryKey: PROVIDERS_QUERY_KEY, queryFn: () => api.listProviders() })
}
