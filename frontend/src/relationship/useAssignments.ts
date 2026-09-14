import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AssignMemberRequest } from '../api/types'
import { patientKey, providerAssignmentsKey } from '../consent/useConsent'

export const coordinatorAssignmentsKey = (id: string) => ['patient', id, 'coordinator-assignments'] as const
export const providerCandidatesKey = (id: string) => ['patient', id, 'provider-candidates'] as const
export const coordinatorCandidatesKey = (id: string) => ['patient', id, 'coordinator-candidates'] as const

/**
 * After any care-team change, refresh everything it can affect: the two assignment lists, both candidate
 * lists (an assigned member drops out / a revoked one returns), the patient itself (a PROVIDER/CARE_TEAM
 * consent decision — and thus a masked field — can flip), and the patients list.
 */
function invalidateCareTeam(queryClient: ReturnType<typeof useQueryClient>, id: string) {
  for (const key of [
    providerAssignmentsKey(id),
    coordinatorAssignmentsKey(id),
    providerCandidatesKey(id),
    coordinatorCandidatesKey(id),
    patientKey(id),
  ]) {
    queryClient.invalidateQueries({ queryKey: key })
  }
  queryClient.invalidateQueries({ queryKey: ['patients'] })
}

/** A patient's current coordinator assignments. */
export function useCoordinatorAssignments(id: string) {
  return useQuery({
    queryKey: coordinatorAssignmentsKey(id),
    queryFn: () => api.listCoordinatorAssignments(id),
    enabled: !!id,
  })
}

/** Providers who can be newly assigned (coordinator/admin only — `enabled` gates the call). */
export function useProviderCandidates(id: string, enabled: boolean) {
  return useQuery({
    queryKey: providerCandidatesKey(id),
    queryFn: () => api.listProviderCandidates(id),
    enabled: !!id && enabled,
  })
}

/** Coordinators who can be newly assigned (coordinator/admin only — `enabled` gates the call). */
export function useCoordinatorCandidates(id: string, enabled: boolean) {
  return useQuery({
    queryKey: coordinatorCandidatesKey(id),
    queryFn: () => api.listCoordinatorCandidates(id),
    enabled: !!id && enabled,
  })
}

export function useAssignProvider(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AssignMemberRequest) => api.assignProvider(id, body),
    onSuccess: () => invalidateCareTeam(queryClient, id),
  })
}

export function useRevokeProviderAssignment(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ assignmentId, expectedVersion }: { assignmentId: string; expectedVersion: number }) =>
      api.revokeProviderAssignment(id, assignmentId, expectedVersion),
    onSuccess: () => invalidateCareTeam(queryClient, id),
  })
}

export function useAssignCoordinator(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AssignMemberRequest) => api.assignCoordinator(id, body),
    onSuccess: () => invalidateCareTeam(queryClient, id),
  })
}

export function useRevokeCoordinatorAssignment(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ assignmentId, expectedVersion }: { assignmentId: string; expectedVersion: number }) =>
      api.revokeCoordinatorAssignment(id, assignmentId, expectedVersion),
    onSuccess: () => invalidateCareTeam(queryClient, id),
  })
}
