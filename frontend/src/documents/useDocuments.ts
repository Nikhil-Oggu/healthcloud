import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'

export const documentsKey = (id: string) => ['patient', id, 'documents'] as const

/** A patient's documents (metadata only). Read is backend-gated to callers who can reach the patient. */
export function useDocuments(id: string) {
  return useQuery({
    queryKey: documentsKey(id),
    queryFn: () => api.listDocuments(id),
    enabled: !!id,
  })
}

/** Upload a document, then refresh the list so the new row (with its scan verdict) appears. */
export function useUploadDocument(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => api.uploadDocument(id, file),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: documentsKey(id) })
    },
  })
}
