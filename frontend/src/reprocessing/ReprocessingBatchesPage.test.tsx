import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ReprocessingBatchesPage } from './ReprocessingBatchesPage'
import type { CoveragePlan, CurrentUser, PageResponse, ReprocessingBatchSummary } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listReprocessingBatches: vi.fn(),
      listCoveragePlans: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listReprocessingBatches = vi.mocked(api.listReprocessingBatches)
const listCoveragePlans = vi.mocked(api.listCoveragePlans)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PLANS: CoveragePlan[] = [
  { id: 'p1', planCode: 'PPO-1', name: 'North PPO', planType: 'PPO', deductibleAmount: 0,
    coinsuranceRate: 0.2, copayAmount: 0, outOfPocketMax: null, active: true, version: 0 },
]
const BATCH: ReprocessingBatchSummary = {
  id: 'b1', batchNumber: 'RPB-ABC12345', coveragePlanId: 'p1', coveragePlanName: 'North PPO',
  status: 'COMPLETED', totalCount: 2, succeededCount: 2, failedCount: 0,
  createdAt: '2026-06-01T10:00:00Z', finishedAt: '2026-06-01T10:00:05Z',
}

/** Build a PageResponse envelope around some rows (defaults describe a single full page). */
function pageOf(
  content: ReprocessingBatchSummary[],
  overrides: Partial<PageResponse<ReprocessingBatchSummary>> = {},
): PageResponse<ReprocessingBatchSummary> {
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

describe('ReprocessingBatchesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listCoveragePlans.mockResolvedValue(PLANS)
  })

  it('lists batches with the plan name and status', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listReprocessingBatches.mockResolvedValue(pageOf([BATCH]))

    renderPage(<ReprocessingBatchesPage />)

    expect(await screen.findByText('RPB-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('North PPO')).toBeInTheDocument()
    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
    // The first fetch uses the defaults: page 0, size 20, no sort, no status filter.
    expect(listReprocessingBatches).toHaveBeenCalledWith({
      page: 0, size: 20, sort: undefined, status: undefined, q: undefined,
    })
  })

  it('shows an empty state and a reviewer sees the Run form', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listReprocessingBatches.mockResolvedValue(pageOf([]))

    renderPage(<ReprocessingBatchesPage />)

    expect(await screen.findByText('No batches yet.')).toBeInTheDocument()
    expect(screen.getByText('Run a batch')).toBeInTheDocument()
  })

  it('a provider (cannot run) sees no Run form', async () => {
    mockUser(['PROVIDER'])
    listReprocessingBatches.mockResolvedValue(pageOf([]))

    renderPage(<ReprocessingBatchesPage />)

    expect(await screen.findByText('No batches yet.')).toBeInTheDocument()
    expect(screen.queryByText('Run a batch')).not.toBeInTheDocument()
  })

  it('typing in the search box queries the server by batch number (debounced)', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listReprocessingBatches.mockResolvedValue(pageOf([BATCH]))
    const user = userEvent.setup()
    renderPage(<ReprocessingBatchesPage />)
    await screen.findByText('RPB-ABC12345')

    await user.type(screen.getByRole('textbox', { name: /search batch/i }), 'ABC12')
    await waitFor(() =>
      expect(listReprocessingBatches).toHaveBeenCalledWith(expect.objectContaining({ q: 'ABC12', page: 0 })),
    )
  })

  it('clicking a column header sorts, the pager advances, and the status filter narrows', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listReprocessingBatches.mockResolvedValue(pageOf([BATCH], { totalElements: 45, totalPages: 3, last: false }))
    const user = userEvent.setup()

    renderPage(<ReprocessingBatchesPage />)
    await screen.findByText('RPB-ABC12345')

    await user.click(screen.getByRole('button', { name: /Batch #/i }))
    await waitFor(() =>
      expect(listReprocessingBatches).toHaveBeenCalledWith(expect.objectContaining({ sort: 'batchNumber,asc' })),
    )

    await user.click(screen.getByRole('button', { name: /go to next page/i }))
    await waitFor(() =>
      expect(listReprocessingBatches).toHaveBeenCalledWith(expect.objectContaining({ page: 1 })),
    )

    await user.click(screen.getByRole('combobox', { name: /status/i }))
    await user.click(await screen.findByRole('option', { name: 'RUNNING' }))
    await waitFor(() =>
      expect(listReprocessingBatches).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'RUNNING', page: 0 }),
      ),
    )
  })
})
