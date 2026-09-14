import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { RecordConsentRequest } from '../api/types'

export const patientKey = (id: string) => ['patient', id] as const
export const consentKey = (id: string) => ['patient', id, 'consent-directives'] as const
export const providerAssignmentsKey = (id: string) => ['patient', id, 'provider-assignments'] as const

/** A single patient in the caller's tenant (field-masked by the backend). */
export function usePatient(id: string) {
  return useQuery({
    queryKey: patientKey(id),
    queryFn: () => api.getPatient(id),
    enabled: !!id,
  })
}

/** A patient's current consent directives. */
export function useConsentDirectives(id: string) {
  return useQuery({
    queryKey: consentKey(id),
    queryFn: () => api.listConsentDirectives(id),
    enabled: !!id,
  })
}

/** A patient's provider assignments — the source for the PROVIDER-scope picker. */
export function useProviderAssignments(id: string) {
  return useQuery({
    queryKey: providerAssignmentsKey(id),
    queryFn: () => api.listProviderAssignments(id),
    enabled: !!id,
  })
}

/** Record a consent directive, then refresh the directive list (a masked field may now change). */
export function useRecordConsent(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: RecordConsentRequest) => api.recordConsent(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: consentKey(id) })
      queryClient.invalidateQueries({ queryKey: patientKey(id) })
      queryClient.invalidateQueries({ queryKey: ['patients'] })
    },
  })
}

/** Revoke a current directive (optimistic-locked), then refresh the list. */
export function useRevokeConsent(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ directiveId, expectedVersion }: { directiveId: string; expectedVersion: number }) =>
      api.revokeConsent(id, directiveId, expectedVersion),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: consentKey(id) })
      queryClient.invalidateQueries({ queryKey: patientKey(id) })
      queryClient.invalidateQueries({ queryKey: ['patients'] })
    },
  })
}
