import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { patientKey } from '../consent/useConsent'
import { PATIENTS_QUERY_KEY } from '../patients/usePatients'
import type { CreateBreakGlassRequest } from '../api/types'

export const BREAK_GLASS_QUERY_KEY = ['break-glass'] as const
export const BREAK_GLASS_ALL_QUERY_KEY = ['break-glass', 'all'] as const

/** The caller's own live break-glass grants (newest first) — a provider's emergency-access home. */
export function useMyBreakGlassGrants() {
  return useQuery({ queryKey: BREAK_GLASS_QUERY_KEY, queryFn: () => api.listBreakGlass() })
}

/** Every live grant in the tenant — the access-review oversight read (AUDITOR/ORG_ADMIN). */
export function useAllBreakGlassGrants() {
  return useQuery({ queryKey: BREAK_GLASS_ALL_QUERY_KEY, queryFn: () => api.listAllBreakGlass() })
}

/** Revoke a grant early (ORG_ADMIN); refresh the oversight list so it drops out immediately. */
export function useRevokeBreakGlass() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => api.revokeBreakGlass(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: BREAK_GLASS_ALL_QUERY_KEY }),
  })
}

/**
 * Break the glass for a patient. On success the patient becomes reachable, so we invalidate that patient's query
 * (the denied detail page then reloads with access), the patient list (the patient now appears), and the grants
 * list.
 */
export function useBreakGlass(patientId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateBreakGlassRequest) => api.breakGlass(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: patientKey(patientId) })
      queryClient.invalidateQueries({ queryKey: PATIENTS_QUERY_KEY })
      queryClient.invalidateQueries({ queryKey: BREAK_GLASS_QUERY_KEY })
    },
  })
}
