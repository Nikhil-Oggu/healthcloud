import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ClaimReviewsPage } from './ClaimReviewsPage'
import type { ClaimReviewSummary, ClaimSummary, CurrentUser, PageResponse, Patient } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listClaimReviews: vi.fn(),
      listPatients: vi.fn(),
      listClaims: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listClaimReviews = vi.mocked(api.listClaimReviews)
const listPatients = vi.mocked(api.listPatients)
const listClaims = vi.mocked(api.listClaims)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const CLAIMS: ClaimSummary[] = [
  { id: 'c1', patientId: 'p1', claimNumber: 'CLM-XYZ02', status: 'REJECTED', serviceDate: '2025-11-01',
    totalChargeAmount: 150, renderingProviderId: null, createdAt: '2026-05-01T10:00:00Z' },
]
const REVIEW: ClaimReviewSummary = {
  id: 'r1', claimId: 'c1', patientId: 'p1', reviewNumber: 'MRV-ABC12345', status: 'OPEN',
  createdAt: '2026-05-02T10:00:00Z',
}

/** Build a PageResponse envelope around some rows (defaults describe a single full page). */
function pageOf(
  content: ClaimReviewSummary[],
  overrides: Partial<PageResponse<ClaimReviewSummary>> = {},
): PageResponse<ClaimReviewSummary> {
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

describe('ClaimReviewsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    listClaims.mockResolvedValue(CLAIMS)
    listClaimReviews.mockResolvedValue(pageOf([REVIEW]))
    // A provider reads the queue but cannot open a review — keeps the list tests focused (no open form).
    mockUser(['PROVIDER'])
  })

  it('lists reviews with the patient name, claim number and status', async () => {
    renderPage(<ClaimReviewsPage />)

    expect(await screen.findByText('MRV-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('CLM-XYZ02')).toBeInTheDocument()
    expect(screen.getByText('OPEN')).toBeInTheDocument()
    // The first fetch uses the defaults: page 0, size 20, no sort, no status filter.
    expect(listClaimReviews).toHaveBeenCalledWith({
      page: 0, size: 20, sort: undefined, status: undefined, q: undefined,
    })
  })

  it('shows an empty state when there are none, and a provider sees no open form', async () => {
    listClaimReviews.mockResolvedValue(pageOf([]))

    renderPage(<ClaimReviewsPage />)

    expect(await screen.findByText('No reviews yet.')).toBeInTheDocument()
    expect(screen.queryByText('New review')).not.toBeInTheDocument()
  })

  it('an opener role sees the New review form', async () => {
    listClaimReviews.mockResolvedValue(pageOf([]))
    mockUser(['CARE_COORDINATOR'])

    renderPage(<ClaimReviewsPage />)

    expect(await screen.findByText('New review')).toBeInTheDocument()
  })

  it('typing in the search box queries the server by review number (debounced)', async () => {
    const user = userEvent.setup()
    renderPage(<ClaimReviewsPage />)
    await screen.findByText('MRV-ABC12345')

    await user.type(screen.getByRole('textbox', { name: /search review/i }), 'ABC12')
    await waitFor(() =>
      expect(listClaimReviews).toHaveBeenCalledWith(expect.objectContaining({ q: 'ABC12', page: 0 })),
    )
  })

  it('clicking a column header sorts by that field, toggling asc/desc', async () => {
    const user = userEvent.setup()
    renderPage(<ClaimReviewsPage />)
    await screen.findByText('MRV-ABC12345')

    await user.click(screen.getByRole('button', { name: /Review #/i }))
    await waitFor(() =>
      expect(listClaimReviews).toHaveBeenCalledWith(expect.objectContaining({ sort: 'reviewNumber,asc', page: 0 })),
    )

    await user.click(screen.getByRole('button', { name: /Review #/i }))
    await waitFor(() =>
      expect(listClaimReviews).toHaveBeenCalledWith(expect.objectContaining({ sort: 'reviewNumber,desc' })),
    )
  })

  it('advancing the pager requests the next page', async () => {
    listClaimReviews.mockResolvedValue(pageOf([REVIEW], { totalElements: 45, totalPages: 3, last: false }))
    const user = userEvent.setup()
    renderPage(<ClaimReviewsPage />)
    await screen.findByText('MRV-ABC12345')

    await user.click(screen.getByRole('button', { name: /go to next page/i }))
    await waitFor(() =>
      expect(listClaimReviews).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20 })),
    )
  })

  it('choosing a status filters the queue by that status', async () => {
    const user = userEvent.setup()
    renderPage(<ClaimReviewsPage />)
    await screen.findByText('MRV-ABC12345')

    await user.click(screen.getByRole('combobox', { name: /status/i }))
    await user.click(await screen.findByRole('option', { name: 'RESOLVED' }))
    await waitFor(() =>
      expect(listClaimReviews).toHaveBeenCalledWith(expect.objectContaining({ status: 'RESOLVED', page: 0 })),
    )
  })
})
