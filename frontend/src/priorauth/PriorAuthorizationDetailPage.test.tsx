import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { PriorAuthorizationDetailPage } from './PriorAuthorizationDetailPage'
import type { CurrentUser, PriorAuthorization, PriorAuthStatusHistory } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getPriorAuthorization: vi.fn(),
      getPriorAuthHistory: vi.fn(),
      changePriorAuthStatus: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getPriorAuthorization = vi.mocked(api.getPriorAuthorization)
const getPriorAuthHistory = vi.mocked(api.getPriorAuthHistory)
const changePriorAuthStatus = vi.mocked(api.changePriorAuthStatus)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const REQUESTED: PriorAuthorization = {
  id: 'pa1', patientId: 'p1', authNumber: 'PA-ABC12345', coveragePlanId: 'pl1', coveragePlanName: 'Standard PPO',
  procedureCodeSystem: 'CPT', procedureCode: '99214', requestedServiceFrom: '2026-06-01',
  requestedServiceTo: '2026-06-30', status: 'REQUESTED', decisionReason: null, decidedBy: null, decidedAt: null,
  requestedBy: 'u9', createdAt: '2026-05-01T10:00:00Z', version: 0,
}
const HISTORY: PriorAuthStatusHistory[] = [
  { id: 'h1', fromStatus: null, toStatus: 'REQUESTED', actorUserId: 'u9', reason: 'Prior authorization requested',
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
      <MemoryRouter initialEntries={['/prior-authorizations/pa1']}>
        <Routes>
          <Route path="/prior-authorizations/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PriorAuthorizationDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getPriorAuthHistory.mockResolvedValue(HISTORY)
  })

  it('renders the header and timeline', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getPriorAuthorization.mockResolvedValue(REQUESTED)

    renderDetail(<PriorAuthorizationDetailPage />)

    expect(await screen.findByText('Prior auth PA-ABC12345')).toBeInTheDocument()
    expect(screen.getByText(/Standard PPO/)).toBeInTheDocument()
    expect(await screen.findByText('Created as REQUESTED')).toBeInTheDocument()
  })

  it('a reviewer sees Approve and Deny', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getPriorAuthorization.mockResolvedValue(REQUESTED)

    renderDetail(<PriorAuthorizationDetailPage />)
    await screen.findByText('Prior auth PA-ABC12345')

    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deny' })).toBeInTheDocument()
    // A reviewer does not cancel a request.
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('a coordinator sees Cancel but not Approve', async () => {
    mockUser(['CARE_COORDINATOR'])
    getPriorAuthorization.mockResolvedValue(REQUESTED)

    renderDetail(<PriorAuthorizationDetailPage />)
    await screen.findByText('Prior auth PA-ABC12345')

    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('approving sends the transition with the loaded version', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getPriorAuthorization.mockResolvedValue(REQUESTED)
    changePriorAuthStatus.mockResolvedValue({ ...REQUESTED, status: 'APPROVED', version: 1 })

    renderDetail(<PriorAuthorizationDetailPage />)
    await screen.findByText('Prior auth PA-ABC12345')

    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))

    await waitFor(() =>
      expect(changePriorAuthStatus).toHaveBeenCalledWith('pa1', {
        targetStatus: 'APPROVED',
        expectedVersion: 0,
        reason: undefined,
      }),
    )
  })

  it('denying prompts for a reason before sending', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getPriorAuthorization.mockResolvedValue(REQUESTED)
    changePriorAuthStatus.mockResolvedValue({ ...REQUESTED, status: 'DENIED', version: 1 })

    renderDetail(<PriorAuthorizationDetailPage />)
    await screen.findByText('Prior auth PA-ABC12345')

    // Clicking Deny does not send yet — it reveals a reason field.
    await userEvent.click(screen.getByRole('button', { name: 'Deny' }))
    expect(changePriorAuthStatus).not.toHaveBeenCalled()

    await userEvent.type(screen.getByLabelText('Reason to deny'), 'Not medically necessary')
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() =>
      expect(changePriorAuthStatus).toHaveBeenCalledWith('pa1', {
        targetStatus: 'DENIED',
        expectedVersion: 0,
        reason: 'Not medically necessary',
      }),
    )
  })
})
