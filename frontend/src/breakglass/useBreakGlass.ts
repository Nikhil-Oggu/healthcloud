import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { patientKey } from '../consent/useConsent'
import { PATIENTS_QUERY_KEY } from '../patients/usePatients'
import type { CreateBreakGlassRequest } from '../api/types'

export const BREAK_GLASS_QUERY_KEY = ['break-glass'] as const

/** The caller's own live break-glass grants (newest first) — a provider's emergency-access home. */
export function useMyBreakGlassGrants() {
  return useQuery({ queryKey: BREAK_GLASS_QUERY_KEY, queryFn: () => api.listBreakGlass() })
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
