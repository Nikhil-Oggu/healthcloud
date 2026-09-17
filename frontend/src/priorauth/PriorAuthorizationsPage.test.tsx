import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { PriorAuthorizationsPage } from './PriorAuthorizationsPage'
import type { CurrentUser, PageResponse, Patient, PriorAuthorizationSummary } from '../api/types'

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
const AUTH: PriorAuthorizationSummary = {
  id: 'pa1', patientId: 'p1', authNumber: 'PA-ABC12345', procedureCodeSystem: 'CPT', procedureCode: '99214',
  status: 'REQUESTED', requestedServiceFrom: '2026-06-01', createdAt: '2026-05-01T10:00:00Z',
}

/** Build a PageResponse envelope around some rows (defaults describe a single full page). */
function pageOf(
  content: PriorAuthorizationSummary[],
  overrides: Partial<PageResponse<PriorAuthorizationSummary>> = {},
): PageResponse<PriorAuthorizationSummary> {
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

describe('PriorAuthorizationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    listCoveragePlans.mockResolvedValue([])
    listPriorAuthorizations.mockResolvedValue(pageOf([AUTH]))
    // A reviewer reads the queue but cannot request — keeps the list tests focused (no create form).
    mockUser(['CLAIMS_REVIEWER'])
  })

  it('lists prior authorizations with the patient name, procedure and status', async () => {
    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('PA-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('99214')).toBeInTheDocument()
    expect(screen.getByText('REQUESTED')).toBeInTheDocument()
    // The first fetch uses the defaults: page 0, size 20, no sort, no status filter.
    expect(listPriorAuthorizations).toHaveBeenCalledWith({ page: 0, size: 20, sort: undefined, status: undefined })
  })

  it('shows an empty state when there are none, and a reviewer sees no request form', async () => {
    listPriorAuthorizations.mockResolvedValue(pageOf([]))

    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('No prior authorizations yet.')).toBeInTheDocument()
    expect(screen.queryByText('New request')).not.toBeInTheDocument()
  })

  it('a requester role sees the New request form', async () => {
    listPriorAuthorizations.mockResolvedValue(pageOf([]))
    mockUser(['CARE_COORDINATOR'])

    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('New request')).toBeInTheDocument()
  })

  it('clicking a column header sorts by that field, toggling asc/desc', async () => {
    const user = userEvent.setup()
    renderPage(<PriorAuthorizationsPage />)
    await screen.findByText('PA-ABC12345')

    await user.click(screen.getByRole('button', { name: /Requested from/i }))
    await waitFor(() =>
      expect(listPriorAuthorizations).toHaveBeenCalledWith(
        expect.objectContaining({ sort: 'requestedServiceFrom,asc', page: 0 }),
      ),
    )

    await user.click(screen.getByRole('button', { name: /Requested from/i }))
    await waitFor(() =>
      expect(listPriorAuthorizations).toHaveBeenCalledWith(
        expect.objectContaining({ sort: 'requestedServiceFrom,desc' }),
      ),
    )
  })

  it('advancing the pager requests the next page', async () => {
    listPriorAuthorizations.mockResolvedValue(pageOf([AUTH], { totalElements: 45, totalPages: 3, last: false }))
    const user = userEvent.setup()
    renderPage(<PriorAuthorizationsPage />)
    await screen.findByText('PA-ABC12345')

    await user.click(screen.getByRole('button', { name: /go to next page/i }))
    await waitFor(() =>
      expect(listPriorAuthorizations).toHaveBeenCalledWith(expect.objectContaining({ page: 1, size: 20 })),
    )
  })

  it('choosing a status filters the queue by that status', async () => {
    const user = userEvent.setup()
    renderPage(<PriorAuthorizationsPage />)
    await screen.findByText('PA-ABC12345')

    await user.click(screen.getByRole('combobox', { name: /status/i }))
    await user.click(await screen.findByRole('option', { name: 'APPROVED' }))
    await waitFor(() =>
      expect(listPriorAuthorizations).toHaveBeenCalledWith(expect.objectContaining({ status: 'APPROVED', page: 0 })),
    )
  })
})
