import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ClaimsPage } from './ClaimsPage'
import type { ClaimSummary, CurrentUser, PageResponse, Patient } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, listClaimsPage: vi.fn(), listPatients: vi.fn() } }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listClaimsPage = vi.mocked(api.listClaimsPage)
const listPatients = vi.mocked(api.listPatients)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const CLAIM: ClaimSummary = {
  id: 'cl1', patientId: 'p1', claimNumber: 'CLM-ABC12345', status: 'DRAFT', serviceDate: '2026-01-10',
  totalChargeAmount: 195.5, renderingProviderId: null, createdAt: '2026-01-10T10:00:00Z',
}

/** Build a PageResponse envelope around some rows (defaults describe a single full page). */
function pageOf(content: ClaimSummary[], overrides: Partial<PageResponse<ClaimSummary>> = {}): PageResponse<ClaimSummary> {
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
    listClaimsPage.mockResolvedValue(pageOf([CLAIM]))
    // A reviewer reads the queue but cannot create — keeps these list tests focused (no create form).
    useCurrentUserMock.mockReturnValue({
      data: { userId: 'u1', email: 'r@northcare.example.org', fullName: 'R',
        organizationId: 'o1', organizationName: 'NorthCare Health', roles: ['CLAIMS_REVIEWER'] } as CurrentUser,
    } as ReturnType<typeof useCurrentUser>)
  })

  it('lists claims with the patient name and status', async () => {
    renderPage(<ClaimsPage />)

    expect(await screen.findByText('CLM-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('DRAFT')).toBeInTheDocument()
    // The first fetch uses the defaults: page 0, size 20, no sort, no status filter.
    expect(listClaimsPage).toHaveBeenCalledWith({ page: 0, size: 20, sort: undefined, status: undefined })
  })

  it('shows an empty state when there are no claims', async () => {
    listClaimsPage.mockResolvedValue(pageOf([]))

    renderPage(<ClaimsPage />)

    expect(await screen.findByText('No claims yet.')).toBeInTheDocument()
    // A reviewer reads the queue but sees no create form.
    expect(screen.queryByText('New claim')).not.toBeInTheDocument()
  })

  it('a create role sees the New claim form', async () => {
    listClaimsPage.mockResolvedValue(pageOf([]))
    useCurrentUserMock.mockReturnValue({
      data: { userId: 'u2', email: 'c@northcare.example.org', fullName: 'C',
        organizationId: 'o1', organizationName: 'NorthCare Health', roles: ['CARE_COORDINATOR'] } as CurrentUser,
    } as ReturnType<typeof useCurrentUser>)

    renderPage(<ClaimsPage />)

    expect(await screen.findByText('New claim')).toBeInTheDocument()
  })

  it('clicking a column header sorts by that field, toggling asc/desc', async () => {
    const user = userEvent.setup()
    renderPage(<ClaimsPage />)
    await screen.findByText('CLM-ABC12345')

    await user.click(screen.getByRole('button', { name: /Service date/i }))
    await waitFor(() =>
      expect(listClaimsPage).toHaveBeenCalledWith(expect.objectContaining({ sort: 'serviceDate,asc', page: 0 })),
    )

    await user.click(screen.getByRole('button', { name: /Service date/i }))
    await waitFor(() =>
      expect(listClaimsPage).toHaveBeenCalledWith(expect.objectContaining({ sort: 'serviceDate,desc' })),
    )
  })

  it('advancing the pager requests the next page', async () => {
    // A multi-page result: 45 total at size 20 → the next-page button is enabled.
    listClaimsPage.mockResolvedValue(pageOf([CLAIM], { totalElements: 45, totalPages: 3, last: false }))
    const user = userEvent.setup()
    renderPage(<ClaimsPage />)
    await screen.findByText('CLM-ABC12345')

    await user.click(screen.getByRole('button', { name: /go to next page/i }))
    await waitFor(() =>
      expect(listClaimsPage).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20 })),
    )
  })

  it('choosing a status filters the queue by that status', async () => {
    const user = userEvent.setup()
    renderPage(<ClaimsPage />)
    await screen.findByText('CLM-ABC12345')

    await user.click(screen.getByRole('combobox', { name: /status/i }))
    await user.click(await screen.findByRole('option', { name: 'SUBMITTED' }))
    await waitFor(() =>
      expect(listClaimsPage).toHaveBeenCalledWith(expect.objectContaining({ status: 'SUBMITTED', page: 0 })),
    )
  })
})
