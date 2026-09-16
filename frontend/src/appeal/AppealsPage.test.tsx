import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { AppealsPage } from './AppealsPage'
import type { AppealSummary, ClaimSummary, CurrentUser, Patient } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listAppeals: vi.fn(),
      listPatients: vi.fn(),
      listClaims: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listAppeals = vi.mocked(api.listAppeals)
const listPatients = vi.mocked(api.listPatients)
const listClaims = vi.mocked(api.listClaims)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const CLAIMS: ClaimSummary[] = [
  { id: 'c1', patientId: 'p1', claimNumber: 'CLM-XYZ02', status: 'REJECTED', serviceDate: '2025-11-01',
    totalChargeAmount: 150, createdAt: '2026-05-01T10:00:00Z' },
]
const APPEALS: AppealSummary[] = [
  { id: 'a1', claimId: 'c1', patientId: 'p1', appealNumber: 'APL-ABC12345', status: 'SUBMITTED',
    createdAt: '2026-05-02T10:00:00Z' },
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

describe('AppealsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    listClaims.mockResolvedValue(CLAIMS)
    // A reviewer reads the queue but cannot submit — keeps the list tests focused (no submit form).
    mockUser(['CLAIMS_REVIEWER'])
  })

  it('lists appeals with the patient name, claim number and status', async () => {
    listAppeals.mockResolvedValue(APPEALS)

    renderPage(<AppealsPage />)

    expect(await screen.findByText('APL-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('CLM-XYZ02')).toBeInTheDocument()
    expect(screen.getByText('SUBMITTED')).toBeInTheDocument()
  })

  it('shows an empty state when there are none, and a reviewer sees no submit form', async () => {
    listAppeals.mockResolvedValue([])

    renderPage(<AppealsPage />)

    expect(await screen.findByText('No appeals yet.')).toBeInTheDocument()
    expect(screen.queryByText('New appeal')).not.toBeInTheDocument()
  })

  it('a submitter role sees the New appeal form', async () => {
    listAppeals.mockResolvedValue([])
    mockUser(['CARE_COORDINATOR'])

    renderPage(<AppealsPage />)

    expect(await screen.findByText('New appeal')).toBeInTheDocument()
  })
})
