import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ClaimsPage } from './ClaimsPage'
import type { ClaimSummary, CurrentUser, Patient } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, listClaims: vi.fn(), listPatients: vi.fn() } }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listClaims = vi.mocked(api.listClaims)
const listPatients = vi.mocked(api.listPatients)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const CLAIMS: ClaimSummary[] = [
  { id: 'cl1', patientId: 'p1', claimNumber: 'CLM-ABC12345', status: 'DRAFT', serviceDate: '2026-01-10', totalChargeAmount: 195.5, createdAt: '2026-01-10T10:00:00Z' },
]

function renderPage(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ClaimsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    // A reviewer reads the queue but cannot create — keeps these list tests focused (no create form).
    useCurrentUserMock.mockReturnValue({
      data: { userId: 'u1', email: 'r@northcare.example.org', fullName: 'R',
        organizationId: 'o1', organizationName: 'NorthCare Health', roles: ['CLAIMS_REVIEWER'] } as CurrentUser,
    } as ReturnType<typeof useCurrentUser>)
  })

  it('lists claims with the patient name and status', async () => {
    listClaims.mockResolvedValue(CLAIMS)

    renderPage(<ClaimsPage />)

    expect(await screen.findByText('CLM-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('DRAFT')).toBeInTheDocument()
  })

  it('shows an empty state when there are no claims', async () => {
    listClaims.mockResolvedValue([])

    renderPage(<ClaimsPage />)

    expect(await screen.findByText('No claims yet.')).toBeInTheDocument()
    // A reviewer reads the queue but sees no create form.
    expect(screen.queryByText('New claim')).not.toBeInTheDocument()
  })

  it('a create role sees the New claim form', async () => {
    listClaims.mockResolvedValue([])
    useCurrentUserMock.mockReturnValue({
      data: { userId: 'u2', email: 'c@northcare.example.org', fullName: 'C',
        organizationId: 'o1', organizationName: 'NorthCare Health', roles: ['CARE_COORDINATOR'] } as CurrentUser,
    } as ReturnType<typeof useCurrentUser>)

    renderPage(<ClaimsPage />)

    expect(await screen.findByText('New claim')).toBeInTheDocument()
  })
})
