import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { PatientCreateRequest } from '../api/types'

export const PATIENTS_QUERY_KEY = ['patients'] as const

/** The current tenant's patients (the backend scopes the list to the caller's organization). */
export function usePatients() {
  return useQuery({
    queryKey: PATIENTS_QUERY_KEY,
    queryFn: api.listPatients,
  })
}

/** Create a patient, then refresh the list so the new row appears. */
export function useCreatePatient() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: PatientCreateRequest) => api.createPatient(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: PATIENTS_QUERY_KEY })
    },
  })
}
