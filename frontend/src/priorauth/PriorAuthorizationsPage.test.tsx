import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { PriorAuthorizationsPage } from './PriorAuthorizationsPage'
import type { CurrentUser, Patient, PriorAuthorizationSummary } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listPriorAuthorizations: vi.fn(),
      listPatients: vi.fn(),
      listCoveragePlans: vi.fn(),
      searchMedicalCodes: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listPriorAuthorizations = vi.mocked(api.listPriorAuthorizations)
const listPatients = vi.mocked(api.listPatients)
const listCoveragePlans = vi.mocked(api.listCoveragePlans)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const AUTHS: PriorAuthorizationSummary[] = [
  { id: 'pa1', patientId: 'p1', authNumber: 'PA-ABC12345', procedureCodeSystem: 'CPT', procedureCode: '99214',
    status: 'REQUESTED', requestedServiceFrom: '2026-06-01', createdAt: '2026-05-01T10:00:00Z' },
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

describe('PriorAuthorizationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    listCoveragePlans.mockResolvedValue([])
    // A reviewer reads the queue but cannot request — keeps the list tests focused (no create form).
    mockUser(['CLAIMS_REVIEWER'])
  })

  it('lists prior authorizations with the patient name, procedure and status', async () => {
    listPriorAuthorizations.mockResolvedValue(AUTHS)

    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('PA-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('99214')).toBeInTheDocument()
    expect(screen.getByText('REQUESTED')).toBeInTheDocument()
  })

  it('shows an empty state when there are none, and a reviewer sees no request form', async () => {
    listPriorAuthorizations.mockResolvedValue([])

    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('No prior authorizations yet.')).toBeInTheDocument()
    expect(screen.queryByText('New request')).not.toBeInTheDocument()
  })

  it('a requester role sees the New request form', async () => {
    listPriorAuthorizations.mockResolvedValue([])
    mockUser(['CARE_COORDINATOR'])

    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('New request')).toBeInTheDocument()
  })
})
