import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { AddCommentRequest, ServiceRequestCreateRequest, StatusChangeRequest } from '../api/types'

export const REQUESTS_QUERY_KEY = ['requests'] as const
export const requestKey = (id: string) => ['requests', id] as const
export const requestHistoryKey = (id: string) => ['requests', id, 'history'] as const
export const requestCommentsKey = (id: string) => ['requests', id, 'comments'] as const

/** The current tenant's requests (backend scopes to the caller's organization). */
export function useRequests() {
  return useQuery({ queryKey: REQUESTS_QUERY_KEY, queryFn: () => api.listRequests() })
}

export function useRequest(id: string) {
  return useQuery({ queryKey: requestKey(id), queryFn: () => api.getRequest(id) })
}

export function useRequestHistory(id: string) {
  return useQuery({ queryKey: requestHistoryKey(id), queryFn: () => api.getRequestHistory(id) })
}

export function useCreateRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ServiceRequestCreateRequest) => api.createRequest(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: REQUESTS_QUERY_KEY }),
  })
}

/** Apply a status transition, then refresh the request, its history, and the list. */
export function useChangeStatus(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: StatusChangeRequest) => api.changeRequestStatus(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: requestKey(id) })
      queryClient.invalidateQueries({ queryKey: requestHistoryKey(id) })
      queryClient.invalidateQueries({ queryKey: REQUESTS_QUERY_KEY })
    },
  })
}

export function useComments(id: string) {
  return useQuery({ queryKey: requestCommentsKey(id), queryFn: () => api.listComments(id) })
}

/** Add a comment, then refresh the request's comment thread. */
export function useAddComment(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: AddCommentRequest) => api.addComment(id, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: requestCommentsKey(id) }),
  })
}
