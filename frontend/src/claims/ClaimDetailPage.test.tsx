import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ClaimDetailPage } from './ClaimDetailPage'
import type { Adjudication, Claim, ClaimStatusHistory, CurrentUser } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getClaim: vi.fn(),
      getClaimHistory: vi.fn(),
      changeClaimStatus: vi.fn(),
      adjudicateClaim: vi.fn(),
      getAdjudication: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getClaim = vi.mocked(api.getClaim)
const getClaimHistory = vi.mocked(api.getClaimHistory)
const changeClaimStatus = vi.mocked(api.changeClaimStatus)
const adjudicateClaim = vi.mocked(api.adjudicateClaim)
const getAdjudication = vi.mocked(api.getAdjudication)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const DRAFT: Claim = {
  id: 'cl1', patientId: 'p1', claimNumber: 'CLM-ABC12345', status: 'DRAFT', serviceDate: '2026-01-10',
  totalChargeAmount: 195.5, createdBy: 'u1', createdAt: '2026-01-10T10:00:00Z', version: 0,
  lines: [
    { id: 'ln1', lineNumber: 1, procedureCodeSystem: 'CPT', procedureCode: '99213', units: 1, chargeAmount: 150.0 },
    { id: 'ln2', lineNumber: 2, procedureCodeSystem: 'CPT', procedureCode: '80053', units: 1, chargeAmount: 45.5 },
  ],
}
const ACCEPTED: Claim = { ...DRAFT, status: 'ACCEPTED', version: 2 }
const ADJUDICATED: Claim = { ...DRAFT, status: 'ADJUDICATED', version: 3 }
const HISTORY: ClaimStatusHistory[] = [
  { id: 'h1', fromStatus: null, toStatus: 'DRAFT', actorUserId: 'u1', reason: 'Claim created', createdAt: '2026-01-10T10:00:00Z' },
]
const ADJUDICATION: Adjudication = {
  id: 'adj1', claimId: 'cl1', adjudicationVersion: 1, outcome: 'ADJUDICATED',
  coveragePlanId: 'pl1', coveragePlanName: 'Standard PPO', eligibilityId: 'el1',
  totalChargeAmount: 195.5, totalAllowedAmount: 195.5, totalPlanPaidAmount: 0, totalMemberResponsibility: 195.5,
  adjudicatedBy: 'u2', adjudicatedAt: '2026-01-11T09:00:00Z',
  lines: [
    { claimLineId: 'ln1', lineNumber: 1, procedureCodeSystem: 'CPT', procedureCode: '99213', outcome: 'COVERED',
      chargeAmount: 150, allowedAmount: 150, copayAmount: 25, deductibleAppliedAmount: 125, coinsuranceAmount: 0,
      oopMaxAppliedAmount: 0, planPaidAmount: 0, memberResponsibility: 150 },
    { claimLineId: 'ln2', lineNumber: 2, procedureCodeSystem: 'CPT', procedureCode: '80053', outcome: 'COVERED',
      chargeAmount: 45.5, allowedAmount: 45.5, copayAmount: 25, deductibleAppliedAmount: 20.5, coinsuranceAmount: 0,
      oopMaxAppliedAmount: 0, planPaidAmount: 0, memberResponsibility: 45.5 },
  ],
}

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
      <MemoryRouter initialEntries={['/claims/cl1']}>
        <Routes>
          <Route path="/claims/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ClaimDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getClaimHistory.mockResolvedValue(HISTORY)
    getAdjudication.mockResolvedValue(ADJUDICATION)
  })

  it('renders the header and the lines', async () => {
    mockUser(['CARE_COORDINATOR'])
    getClaim.mockResolvedValue(DRAFT)

    renderDetail(<ClaimDetailPage />)

    expect(await screen.findByText('Claim CLM-ABC12345')).toBeInTheDocument()
    expect(screen.getByText('99213')).toBeInTheDocument()
    expect(screen.getByText('80053')).toBeInTheDocument()
    // A coordinator may submit a DRAFT.
    expect(screen.getByRole('button', { name: 'Submit' })).toBeInTheDocument()
  })

  it('a wrong-role user sees no lifecycle actions on a DRAFT', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getClaim.mockResolvedValue(DRAFT)

    renderDetail(<ClaimDetailPage />)
    await screen.findByText('Claim CLM-ABC12345')

    // A reviewer submits nothing; on a DRAFT there is no accept/reject either.
    expect(screen.queryByRole('button', { name: 'Submit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
  })

  it('a reviewer can adjudicate an ACCEPTED claim', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getClaim.mockResolvedValue(ACCEPTED)
    adjudicateClaim.mockResolvedValue(ADJUDICATION)

    renderDetail(<ClaimDetailPage />)
    await screen.findByText('Claim CLM-ABC12345')

    await userEvent.click(screen.getByRole('button', { name: 'Adjudicate' }))

    await waitFor(() => expect(adjudicateClaim).toHaveBeenCalledWith('cl1'))
  })

  it('shows the adjudication breakdown once adjudicated', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getClaim.mockResolvedValue(ADJUDICATED)

    renderDetail(<ClaimDetailPage />)

    expect(await screen.findByText('Adjudication')).toBeInTheDocument()
    // The covering plan name appears once the adjudication query resolves.
    expect(await screen.findByText('Standard PPO')).toBeInTheDocument()
    // Two COVERED line outcomes in the breakdown.
    expect(screen.getAllByText('COVERED').length).toBeGreaterThanOrEqual(2)
  })

  it('submitting sends the transition with the loaded version', async () => {
    mockUser(['CARE_COORDINATOR'])
    getClaim.mockResolvedValue(DRAFT)
    changeClaimStatus.mockResolvedValue({ ...DRAFT, status: 'SUBMITTED', version: 1 })

    renderDetail(<ClaimDetailPage />)
    await screen.findByText('Claim CLM-ABC12345')

    await userEvent.click(screen.getByRole('button', { name: 'Submit' }))

    await waitFor(() =>
      expect(changeClaimStatus).toHaveBeenCalledWith('cl1', {
        targetStatus: 'SUBMITTED',
        expectedVersion: 0,
        reason: undefined,
      }),
    )
  })
})
