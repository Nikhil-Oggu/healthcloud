import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

export const PROVIDERS_QUERY_KEY = ['providers'] as const

/** Roles the backend allows to read the provider directory (the claim-create audience). */
export const DIRECTORY_ROLES = ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'] as const

/**
 * The current tenant's active providers — the source for the rendering-provider picker on claim create (and for
 * resolving a claim's rendering-provider id → name on the detail page). The backend gates this to the
 * claim-create roles (see {@link DIRECTORY_ROLES}), so a viewer without those roles gets a 403; pass
 * {@code enabled: false} for such a viewer (e.g. a PATIENT or CLAIMS_REVIEWER reading a claim) so the gated
 * endpoint is never called. Callers that only display a name should treat an empty result as "no name to show".
 */
export function useProviders(enabled = true) {
  return useQuery({ queryKey: PROVIDERS_QUERY_KEY, queryFn: () => api.listProviders(), enabled })
}
