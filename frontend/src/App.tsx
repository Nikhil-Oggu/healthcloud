import { createBrowserRouter, RouterProvider } from 'react-router-dom'
import { AppLayout } from './layout/AppLayout'
import { ProtectedRoute } from './auth/ProtectedRoute'
import { LoginPage } from './auth/LoginPage'
import { HomePage } from './pages/HomePage'
import { PatientsPage } from './patients/PatientsPage'
import { PatientDetailPage } from './patients/PatientDetailPage'
import { RequestsPage } from './requests/RequestsPage'
import { RequestDetailPage } from './requests/RequestDetailPage'
import { ClaimsPage } from './claims/ClaimsPage'
import { ClaimDetailPage } from './claims/ClaimDetailPage'
import { DeniedPage } from './pages/DeniedPage'
import { NotFoundPage } from './pages/NotFoundPage'

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <ProtectedRoute>
        <AppLayout />
      </ProtectedRoute>
    ),
    children: [
      { index: true, element: <HomePage /> },
      { path: 'patients', element: <PatientsPage /> },
      { path: 'patients/:id', element: <PatientDetailPage /> },
      { path: 'requests', element: <RequestsPage /> },
      { path: 'requests/:id', element: <RequestDetailPage /> },
      { path: 'claims', element: <ClaimsPage /> },
      { path: 'claims/:id', element: <ClaimDetailPage /> },
      { path: 'denied', element: <DeniedPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])

export function App() {
  return <RouterProvider router={router} />
}
