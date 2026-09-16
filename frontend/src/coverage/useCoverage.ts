import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type {
  AddFeeScheduleRequest,
  AddPlanExclusionRequest,
  AddPriorAuthRequirementRequest,
  CreateCoveragePlanRequest,
} from '../api/types'

export const COVERAGE_PLANS_QUERY_KEY = ['coverage-plans'] as const
export const coveragePlanKey = (id: string) => ['coverage-plans', id] as const
export const exclusionsKey = (id: string) => ['coverage-plans', id, 'exclusions'] as const
export const feeScheduleKey = (id: string) => ['coverage-plans', id, 'fee-schedule'] as const
export const priorAuthRequirementsKey = (id: string) =>
  ['coverage-plans', id, 'prior-auth-requirements'] as const

/** The current tenant's coverage plans (reads open to any same-tenant user). */
export function useCoveragePlans() {
  return useQuery({ queryKey: COVERAGE_PLANS_QUERY_KEY, queryFn: () => api.listCoveragePlans() })
}

export function useCoveragePlan(id: string) {
  return useQuery({ queryKey: coveragePlanKey(id), queryFn: () => api.getCoveragePlan(id) })
}

/** Create a coverage plan (ORG_ADMIN), then refresh the list. */
export function useCreateCoveragePlan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateCoveragePlanRequest) => api.createCoveragePlan(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: COVERAGE_PLANS_QUERY_KEY }),
  })
}

export function useExclusions(planId: string) {
  return useQuery({ queryKey: exclusionsKey(planId), queryFn: () => api.listExclusions(planId) })
}

/** Add an exclusion to a plan, then refresh that plan's exclusion list. */
export function useAddExclusion(planId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AddPlanExclusionRequest) => api.addExclusion(planId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: exclusionsKey(planId) }),
  })
}

/** Remove an exclusion from a plan, then refresh that plan's exclusion list. */
export function useRemoveExclusion(planId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (exclusionId: string) => api.removeExclusion(planId, exclusionId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: exclusionsKey(planId) }),
  })
}

export function useFeeSchedule(planId: string) {
  return useQuery({ queryKey: feeScheduleKey(planId), queryFn: () => api.listFeeSchedule(planId) })
}

/** Price a procedure on a plan, then refresh that plan's fee schedule. */
export function useAddFeeSchedule(planId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AddFeeScheduleRequest) => api.addFeeSchedule(planId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: feeScheduleKey(planId) }),
  })
}

/** Remove a fee-schedule entry from a plan, then refresh that plan's fee schedule. */
export function useRemoveFeeSchedule(planId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (entryId: string) => api.removeFeeSchedule(planId, entryId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: feeScheduleKey(planId) }),
  })
}

export function usePriorAuthRequirements(planId: string) {
  return useQuery({
    queryKey: priorAuthRequirementsKey(planId),
    queryFn: () => api.listPriorAuthRequirements(planId),
  })
}

/** Require prior auth for a procedure on a plan, then refresh that plan's requirement list. */
export function useAddPriorAuthRequirement(planId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AddPriorAuthRequirementRequest) => api.addPriorAuthRequirement(planId, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priorAuthRequirementsKey(planId) }),
  })
}

/** Remove a prior-auth requirement from a plan, then refresh that plan's requirement list. */
export function useRemovePriorAuthRequirement(planId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (requirementId: string) => api.removePriorAuthRequirement(planId, requirementId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: priorAuthRequirementsKey(planId) }),
  })
}
