import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ReferralsPage } from './ReferralsPage'
import type { CurrentUser, Patient, ReferralSummary } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listReferrals: vi.fn(),
      listPatients: vi.fn(),
      searchMedicalCodes: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listReferrals = vi.mocked(api.listReferrals)
const listPatients = vi.mocked(api.listPatients)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const REFERRALS: ReferralSummary[] = [
  { id: 'r1', patientId: 'p1', referralNumber: 'REF-ABC12345', specialty: 'Cardiology',
    reasonCodeSystem: 'ICD10CM', reasonCode: 'I10', status: 'REQUESTED', createdAt: '2026-05-01T10:00:00Z' },
]

function mockUser(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: { userId: 'u1', email: 'x@northcare.example.org', fullName: 'X',
      organizationId: 'o1', organizationName: 'NorthCare Health', roles } as CurrentUser,
  } as ReturnType<typeof useCurrentUser>)
}

function renderPage(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReferralsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    // A reviewer reads the queue but cannot request — keeps the list tests focused (no create form).
    mockUser(['CLAIMS_REVIEWER'])
  })

  it('lists referrals with the patient name, specialty, reason and status', async () => {
    listReferrals.mockResolvedValue(REFERRALS)

    renderPage(<ReferralsPage />)

    expect(await screen.findByText('REF-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('Cardiology')).toBeInTheDocument()
    expect(screen.getByText('I10')).toBeInTheDocument()
    expect(screen.getByText('REQUESTED')).toBeInTheDocument()
  })

  it('shows an empty state when there are none, and a reviewer sees no request form', async () => {
    listReferrals.mockResolvedValue([])

    renderPage(<ReferralsPage />)

    expect(await screen.findByText('No referrals yet.')).toBeInTheDocument()
    expect(screen.queryByText('New request')).not.toBeInTheDocument()
  })

  it('a requester role sees the New request form', async () => {
    listReferrals.mockResolvedValue([])
    mockUser(['CARE_COORDINATOR'])

    renderPage(<ReferralsPage />)

    expect(await screen.findByText('New request')).toBeInTheDocument()
  })
})
