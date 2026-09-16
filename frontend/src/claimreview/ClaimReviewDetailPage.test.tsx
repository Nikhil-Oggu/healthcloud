import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ClaimReviewDetailPage } from './ClaimReviewDetailPage'
import type { ClaimReview, ClaimReviewStatusHistory, ClaimSummary, CurrentUser } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getClaimReview: vi.fn(),
      getClaimReviewHistory: vi.fn(),
      changeClaimReviewStatus: vi.fn(),
      listClaims: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getClaimReview = vi.mocked(api.getClaimReview)
const getClaimReviewHistory = vi.mocked(api.getClaimReviewHistory)
const changeClaimReviewStatus = vi.mocked(api.changeClaimReviewStatus)
const listClaims = vi.mocked(api.listClaims)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const OPEN: ClaimReview = {
  id: 'r1', claimId: 'c1', patientId: 'p1', reviewNumber: 'MRV-ABC12345',
  reason: 'Flagged for a manual look.', status: 'OPEN', resolution: null,
  openedBy: 'u9', resolvedBy: null, resolvedAt: null, createdAt: '2026-05-02T10:00:00Z', version: 0,
}
const CLAIMS: ClaimSummary[] = [
  { id: 'c1', patientId: 'p1', claimNumber: 'CLM-XYZ02', status: 'REJECTED', serviceDate: '2025-11-01',
    totalChargeAmount: 150, createdAt: '2026-05-01T10:00:00Z' },
]
const HISTORY: ClaimReviewStatusHistory[] = [
  { id: 'h1', fromStatus: null, toStatus: 'OPEN', actorUserId: 'u9', reason: 'Review opened',
    createdAt: '2026-05-02T10:00:00Z' },
]

function mockUser(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: { userId: 'u1', email: 'x@northcare.example.org', fullName: 'X',
      organizationId: 'o1', organizationName: 'NorthCare Health', roles } as CurrentUser,
  } as ReturnType<typeof useCurrentUser>)
}

function renderDetail(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/claim-reviews/r1']}>
        <Routes>
          <Route path="/claim-reviews/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ClaimReviewDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getClaimReviewHistory.mockResolvedValue(HISTORY)
    listClaims.mockResolvedValue(CLAIMS)
  })

  it('renders the header (with the reviewed claim) and timeline', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getClaimReview.mockResolvedValue(OPEN)

    renderDetail(<ClaimReviewDetailPage />)

    expect(await screen.findByText('Review MRV-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('CLM-XYZ02')).toBeInTheDocument()
    expect(await screen.findByText('Created as OPEN')).toBeInTheDocument()
  })

  it('a reviewer sees Resolve and Cancel', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getClaimReview.mockResolvedValue(OPEN)

    renderDetail(<ClaimReviewDetailPage />)
    await screen.findByText('Review MRV-ABC12345')

    expect(screen.getByRole('button', { name: 'Resolve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('a coordinator sees Cancel but not Resolve', async () => {
    mockUser(['CARE_COORDINATOR'])
    getClaimReview.mockResolvedValue(OPEN)

    renderDetail(<ClaimReviewDetailPage />)
    await screen.findByText('Review MRV-ABC12345')

    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument()
  })

  it('resolving prompts for a reason before sending', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getClaimReview.mockResolvedValue(OPEN)
    changeClaimReviewStatus.mockResolvedValue({ ...OPEN, status: 'RESOLVED', version: 1 })

    renderDetail(<ClaimReviewDetailPage />)
    await screen.findByText('Review MRV-ABC12345')

    // Clicking Resolve does not send yet — it reveals a reason field.
    await userEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    expect(changeClaimReviewStatus).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Reason to resolve'), 'Charges verified')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() =>
      expect(changeClaimReviewStatus).toHaveBeenCalledWith('r1', {
        targetStatus: 'RESOLVED',
        expectedVersion: 0,
        reason: 'Charges verified',
      }),
    )
  })
})
