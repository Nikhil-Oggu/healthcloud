import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ReferralsPage } from './ReferralsPage'
import type { CurrentUser, PageResponse, Patient, ReferralSummary } from '../api/types'

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
const REFERRAL: ReferralSummary = {
  id: 'r1', patientId: 'p1', referralNumber: 'REF-ABC12345', specialty: 'Cardiology',
  reasonCodeSystem: 'ICD10CM', reasonCode: 'I10', status: 'REQUESTED', createdAt: '2026-05-01T10:00:00Z',
}

/** Build a PageResponse envelope around some rows (defaults describe a single full page). */
function pageOf(
  content: ReferralSummary[],
  overrides: Partial<PageResponse<ReferralSummary>> = {},
): PageResponse<ReferralSummary> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    first: true,
    last: true,
    ...overrides,
  }
}

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
    listReferrals.mockResolvedValue(pageOf([REFERRAL]))
    // A reviewer reads the queue but cannot request — keeps the list tests focused (no create form).
    mockUser(['CLAIMS_REVIEWER'])
  })

  it('lists referrals with the patient name, specialty, reason and status', async () => {
    renderPage(<ReferralsPage />)

    expect(await screen.findByText('REF-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('Cardiology')).toBeInTheDocument()
    expect(screen.getByText('I10')).toBeInTheDocument()
    expect(screen.getByText('REQUESTED')).toBeInTheDocument()
    // The first fetch uses the defaults: page 0, size 20, no sort, no status filter.
    expect(listReferrals).toHaveBeenCalledWith({
      page: 0, size: 20, sort: undefined, status: undefined, q: undefined,
    })
  })

  it('shows an empty state when there are none, and a reviewer sees no request form', async () => {
    listReferrals.mockResolvedValue(pageOf([]))

    renderPage(<ReferralsPage />)

    expect(await screen.findByText('No referrals yet.')).toBeInTheDocument()
    expect(screen.queryByText('New request')).not.toBeInTheDocument()
  })

  it('a requester role sees the New request form', async () => {
    listReferrals.mockResolvedValue(pageOf([]))
    mockUser(['CARE_COORDINATOR'])

    renderPage(<ReferralsPage />)

    expect(await screen.findByText('New request')).toBeInTheDocument()
  })

  it('typing in the search box queries the server by referral number (debounced)', async () => {
    const user = userEvent.setup()
    renderPage(<ReferralsPage />)
    await screen.findByText('REF-ABC12345')

    await user.type(screen.getByRole('textbox', { name: /search referral/i }), 'ABC12')
    await waitFor(() =>
      expect(listReferrals).toHaveBeenCalledWith(expect.objectContaining({ q: 'ABC12', page: 0 })),
    )
  })

  it('clicking a column header sorts by that field, toggling asc/desc', async () => {
    const user = userEvent.setup()
    renderPage(<ReferralsPage />)
    await screen.findByText('REF-ABC12345')

    await user.click(screen.getByRole('button', { name: /Specialty/i }))
    await waitFor(() =>
      expect(listReferrals).toHaveBeenCalledWith(expect.objectContaining({ sort: 'specialty,asc', page: 0 })),
    )

    await user.click(screen.getByRole('button', { name: /Specialty/i }))
    await waitFor(() =>
      expect(listReferrals).toHaveBeenCalledWith(expect.objectContaining({ sort: 'specialty,desc' })),
    )
  })

  it('advancing the pager requests the next page', async () => {
    listReferrals.mockResolvedValue(pageOf([REFERRAL], { totalElements: 45, totalPages: 3, last: false }))
    const user = userEvent.setup()
    renderPage(<ReferralsPage />)
    await screen.findByText('REF-ABC12345')

    await user.click(screen.getByRole('button', { name: /go to next page/i }))
    await waitFor(() =>
      expect(listReferrals).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20 })),
    )
  })

  it('choosing a status filters the queue by that status', async () => {
    const user = userEvent.setup()
    renderPage(<ReferralsPage />)
    await screen.findByText('REF-ABC12345')

    await user.click(screen.getByRole('combobox', { name: /status/i }))
    await user.click(await screen.findByRole('option', { name: 'APPROVED' }))
    await waitFor(() =>
      expect(listReferrals).toHaveBeenCalledWith(expect.objectContaining({ status: 'APPROVED', page: 0 })),
    )
  })
})
