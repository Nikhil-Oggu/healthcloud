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
import { CoveragePlansPage } from './coverage/CoveragePlansPage'
import { CoveragePlanDetailPage } from './coverage/CoveragePlanDetailPage'
import { PriorAuthorizationsPage } from './priorauth/PriorAuthorizationsPage'
import { PriorAuthorizationDetailPage } from './priorauth/PriorAuthorizationDetailPage'
import { ReferralsPage } from './referral/ReferralsPage'
import { ReferralDetailPage } from './referral/ReferralDetailPage'
import { AppealsPage } from './appeal/AppealsPage'
import { AppealDetailPage } from './appeal/AppealDetailPage'
import { ClaimReviewsPage } from './claimreview/ClaimReviewsPage'
import { ClaimReviewDetailPage } from './claimreview/ClaimReviewDetailPage'
import { ReprocessingBatchesPage } from './reprocessing/ReprocessingBatchesPage'
import { ReprocessingBatchDetailPage } from './reprocessing/ReprocessingBatchDetailPage'
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
      { path: 'coverage-plans', element: <CoveragePlansPage /> },
      { path: 'coverage-plans/:id', element: <CoveragePlanDetailPage /> },
      { path: 'prior-authorizations', element: <PriorAuthorizationsPage /> },
      { path: 'prior-authorizations/:id', element: <PriorAuthorizationDetailPage /> },
      { path: 'referrals', element: <ReferralsPage /> },
      { path: 'referrals/:id', element: <ReferralDetailPage /> },
      { path: 'appeals', element: <AppealsPage /> },
      { path: 'appeals/:id', element: <AppealDetailPage /> },
      { path: 'claim-reviews', element: <ClaimReviewsPage /> },
      { path: 'claim-reviews/:id', element: <ClaimReviewDetailPage /> },
      { path: 'reprocessing', element: <ReprocessingBatchesPage /> },
      { path: 'reprocessing/:id', element: <ReprocessingBatchDetailPage /> },
      { path: 'denied', element: <DeniedPage /> },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])

export function App() {
  return <RouterProvider router={router} />
}
