import type { ReactNode } from 'react'
import { Navigate } from 'react-router-dom'
import { ApiClientError } from '../api/client'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { useCurrentUser } from './useAuth'

/**
 * Gate for authenticated areas. While the session resolves we show a spinner; a 401 (or no user)
 * redirects to the login page; any other error is surfaced. Authorization always rests on the
 * backend — this only decides what the browser renders.
 */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { data: user, isLoading, error } = useCurrentUser()

  if (isLoading) {
    return <LoadingScreen />
  }

  if (error) {
    if (error instanceof ApiClientError && error.status === 401) {
      return <Navigate to="/login" replace />
    }
    return <ErrorScreen error={error} />
  }

  if (!user) {
    return <Navigate to="/login" replace />
  }

  return <>{children}</>
}
