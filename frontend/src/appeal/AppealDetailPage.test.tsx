import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { AppealDetailPage } from './AppealDetailPage'
import type { Appeal, AppealStatusHistory, ClaimSummary, CurrentUser } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getAppeal: vi.fn(),
      getAppealHistory: vi.fn(),
      changeAppealStatus: vi.fn(),
      listClaims: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getAppeal = vi.mocked(api.getAppeal)
const getAppealHistory = vi.mocked(api.getAppealHistory)
const changeAppealStatus = vi.mocked(api.changeAppealStatus)
const listClaims = vi.mocked(api.listClaims)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const SUBMITTED: Appeal = {
  id: 'a1', claimId: 'c1', patientId: 'p1', appealNumber: 'APL-ABC12345',
  reason: 'Rejected in error; documentation attached.', status: 'SUBMITTED', decisionReason: null,
  decidedBy: null, decidedAt: null, submittedBy: 'u9', createdAt: '2026-05-02T10:00:00Z', version: 0,
}
const CLAIMS: ClaimSummary[] = [
  { id: 'c1', patientId: 'p1', claimNumber: 'CLM-XYZ02', status: 'REJECTED', serviceDate: '2025-11-01',
    totalChargeAmount: 150, createdAt: '2026-05-01T10:00:00Z' },
]
const HISTORY: AppealStatusHistory[] = [
  { id: 'h1', fromStatus: null, toStatus: 'SUBMITTED', actorUserId: 'u9', reason: 'Appeal submitted',
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
      <MemoryRouter initialEntries={['/appeals/a1']}>
        <Routes>
          <Route path="/appeals/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AppealDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getAppealHistory.mockResolvedValue(HISTORY)
    listClaims.mockResolvedValue(CLAIMS)
  })

  it('renders the header (with the disputed claim) and timeline', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getAppeal.mockResolvedValue(SUBMITTED)

    renderDetail(<AppealDetailPage />)

    expect(await screen.findByText('Appeal APL-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('CLM-XYZ02')).toBeInTheDocument()
    expect(await screen.findByText('Created as SUBMITTED')).toBeInTheDocument()
  })

  it('a reviewer sees Uphold and Overturn but not Withdraw', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getAppeal.mockResolvedValue(SUBMITTED)

    renderDetail(<AppealDetailPage />)
    await screen.findByText('Appeal APL-ABC12345')

    expect(screen.getByRole('button', { name: 'Uphold' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Overturn' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Withdraw' })).not.toBeInTheDocument()
  })

  it('a submitter sees Withdraw but not Uphold/Overturn', async () => {
    mockUser(['CARE_COORDINATOR'])
    getAppeal.mockResolvedValue(SUBMITTED)

    renderDetail(<AppealDetailPage />)
    await screen.findByText('Appeal APL-ABC12345')

    expect(screen.getByRole('button', { name: 'Withdraw' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Uphold' })).not.toBeInTheDocument()
  })

  it('upholding prompts for a reason before sending (every transition needs one)', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getAppeal.mockResolvedValue(SUBMITTED)
    changeAppealStatus.mockResolvedValue({ ...SUBMITTED, status: 'UPHELD', version: 1 })

    renderDetail(<AppealDetailPage />)
    await screen.findByText('Appeal APL-ABC12345')

    // Clicking Uphold does not send yet — it reveals a reason field.
    await userEvent.click(screen.getByRole('button', { name: 'Uphold' }))
    expect(changeAppealStatus).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Reason to uphold'), 'Original decision correct')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() =>
      expect(changeAppealStatus).toHaveBeenCalledWith('a1', {
        targetStatus: 'UPHELD',
        expectedVersion: 0,
        reason: 'Original decision correct',
      }),
    )
  })
})
