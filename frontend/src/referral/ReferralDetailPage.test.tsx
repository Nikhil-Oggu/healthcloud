import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ReferralDetailPage } from './ReferralDetailPage'
import type { CurrentUser, Referral, ReferralStatusHistory } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getReferral: vi.fn(),
      getReferralHistory: vi.fn(),
      changeReferralStatus: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getReferral = vi.mocked(api.getReferral)
const getReferralHistory = vi.mocked(api.getReferralHistory)
const changeReferralStatus = vi.mocked(api.changeReferralStatus)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const REQUESTED: Referral = {
  id: 'r1', patientId: 'p1', referralNumber: 'REF-ABC12345', specialty: 'Cardiology',
  reasonCodeSystem: 'ICD10CM', reasonCode: 'I10', status: 'REQUESTED', decisionReason: null,
  decidedBy: null, decidedAt: null, requestedBy: 'u9', createdAt: '2026-05-01T10:00:00Z', version: 0,
}
const HISTORY: ReferralStatusHistory[] = [
  { id: 'h1', fromStatus: null, toStatus: 'REQUESTED', actorUserId: 'u9', reason: 'Referral requested',
    createdAt: '2026-05-01T10:00:00Z' },
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
      <MemoryRouter initialEntries={['/referrals/r1']}>
        <Routes>
          <Route path="/referrals/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReferralDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getReferralHistory.mockResolvedValue(HISTORY)
  })

  it('renders the header and timeline', async () => {
    mockUser(['CARE_COORDINATOR'])
    getReferral.mockResolvedValue(REQUESTED)

    renderDetail(<ReferralDetailPage />)

    expect(await screen.findByText('Referral REF-ABC12345')).toBeInTheDocument()
    expect(screen.getByText(/Cardiology/)).toBeInTheDocument()
    expect(await screen.findByText('Created as REQUESTED')).toBeInTheDocument()
  })

  it('a coordinator sees Approve, Deny and Cancel', async () => {
    mockUser(['CARE_COORDINATOR'])
    getReferral.mockResolvedValue(REQUESTED)

    renderDetail(<ReferralDetailPage />)
    await screen.findByText('Referral REF-ABC12345')

    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deny' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
  })

  it('a provider sees Cancel but not Approve (referrals are decided by coordinators)', async () => {
    mockUser(['PROVIDER'])
    getReferral.mockResolvedValue(REQUESTED)

    renderDetail(<ReferralDetailPage />)
    await screen.findByText('Referral REF-ABC12345')

    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('approving sends the transition with the loaded version', async () => {
    mockUser(['CARE_COORDINATOR'])
    getReferral.mockResolvedValue(REQUESTED)
    changeReferralStatus.mockResolvedValue({ ...REQUESTED, status: 'APPROVED', version: 1 })

    renderDetail(<ReferralDetailPage />)
    await screen.findByText('Referral REF-ABC12345')

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    await waitFor(() =>
      expect(changeReferralStatus).toHaveBeenCalledWith('r1', {
        targetStatus: 'APPROVED',
        expectedVersion: 0,
        reason: undefined,
      }),
    )
  })

  it('denying prompts for a reason before sending', async () => {
    mockUser(['CARE_COORDINATOR'])
    getReferral.mockResolvedValue(REQUESTED)
    changeReferralStatus.mockResolvedValue({ ...REQUESTED, status: 'DENIED', version: 1 })

    renderDetail(<ReferralDetailPage />)
    await screen.findByText('Referral REF-ABC12345')

    // Clicking Deny does not send yet — it reveals a reason field.
    await userEvent.click(screen.getByRole('button', { name: 'Deny' }))
    expect(changeReferralStatus).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Reason to deny'), 'Specialist not in network')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() =>
      expect(changeReferralStatus).toHaveBeenCalledWith('r1', {
        targetStatus: 'DENIED',
        expectedVersion: 0,
        reason: 'Specialist not in network',
      }),
    )
  })
})
