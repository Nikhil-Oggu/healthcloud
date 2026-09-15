import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { EnrollEligibilityRequest } from '../api/types'

// Patient-nested, so the key follows the ['patient', id, …] convention used by the care-team/consent hooks.
export const eligibilityKey = (patientId: string) => ['patient', patientId, 'eligibility'] as const

/** A patient's eligibility records (reads open to any same-tenant user with access to the patient). */
export function useEligibility(patientId: string) {
  return useQuery({
    queryKey: eligibilityKey(patientId),
    queryFn: () => api.listEligibility(patientId),
    enabled: !!patientId,
  })
}

/** Enroll a patient in a coverage plan (CARE_COORDINATOR/ORG_ADMIN), then refresh the eligibility list. */
export function useEnrollEligibility(patientId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: EnrollEligibilityRequest) => api.enrollEligibility(patientId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: eligibilityKey(patientId) }),
  })
}
