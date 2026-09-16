import { useMutation, useQuery } from '@tanstack/react-query'
import { api } from '../api/client'

export const AUDIT_EVENTS_QUERY_KEY = ['audit-events'] as const

/** The tenant's recent security audit events (newest first) — an AUDITOR/ORG_ADMIN read. */
export function useAuditEvents() {
  return useQuery({ queryKey: AUDIT_EVENTS_QUERY_KEY, queryFn: () => api.listAuditEvents() })
}

/**
 * Verify the tenant's audit chain on demand. Modelled as a mutation because it is a user-triggered action whose
 * fresh result (and in-flight state, for the button) we want each click — the server recomputes the per-org HMAC
 * chain and returns the verdict; no crypto runs in the browser.
 */
export function useVerifyAuditChain() {
  return useMutation({ mutationFn: () => api.verifyAuditChain() })
}
