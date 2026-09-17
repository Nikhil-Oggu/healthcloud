import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AuditAction } from '../api/types'

export const AUDIT_EVENTS_QUERY_KEY = ['audit-events'] as const

/** The parameters that drive a page of the audit trail (§Phase 9). */
export interface AuditEventsPageParams {
  action?: AuditAction
  /** Free-text search — a case-insensitive match on the correlation id or resource id (§Phase 9 slice 8). */
  q?: string
  page: number
  size: number
  sort?: string
}

/**
 * A page of the tenant's security audit events (§Phase 9) — an AUDITOR/ORG_ADMIN read. The query key carries the
 * params so a page/sort/action-filter change refetches. `keepPreviousData` keeps rows on screen while paging.
 */
export function useAuditEvents(params: AuditEventsPageParams) {
  return useQuery({
    queryKey: [...AUDIT_EVENTS_QUERY_KEY, 'page', params],
    queryFn: () => api.listAuditEvents(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * Verify the tenant's audit chain on demand. Modelled as a mutation because it is a user-triggered action whose
 * fresh result (and in-flight state, for the button) we want each click — the server recomputes the per-org HMAC
 * chain and returns the verdict; no crypto runs in the browser.
 */
export function useVerifyAuditChain() {
  return useMutation({ mutationFn: () => api.verifyAuditChain() })
}
