import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { ReprocessingBatchesPage } from './ReprocessingBatchesPage'
import type { CoveragePlan, CurrentUser, ReprocessingBatchSummary } from '../api/types'

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
const BATCHES: ReprocessingBatchSummary[] = [
  { id: 'b1', batchNumber: 'RPB-ABC12345', coveragePlanId: 'p1', coveragePlanName: 'North PPO',
    status: 'COMPLETED', totalCount: 2, succeededCount: 2, failedCount: 0,
    createdAt: '2026-06-01T10:00:00Z', finishedAt: '2026-06-01T10:00:05Z' },
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

describe('ReprocessingBatchesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listCoveragePlans.mockResolvedValue(PLANS)
  })

  it('lists batches with the plan name and status', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listReprocessingBatches.mockResolvedValue(BATCHES)

    renderPage(<ReprocessingBatchesPage />)

    expect(await screen.findByText('RPB-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('North PPO')).toBeInTheDocument()
    expect(screen.getByText('COMPLETED')).toBeInTheDocument()
  })

  it('shows an empty state and a reviewer sees the Run form', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listReprocessingBatches.mockResolvedValue([])

    renderPage(<ReprocessingBatchesPage />)

    expect(await screen.findByText('No batches yet.')).toBeInTheDocument()
    expect(screen.getByText('Run a batch')).toBeInTheDocument()
  })

  it('a provider (cannot run) sees no Run form', async () => {
    mockUser(['PROVIDER'])
    listReprocessingBatches.mockResolvedValue([])

    renderPage(<ReprocessingBatchesPage />)

    expect(await screen.findByText('No batches yet.')).toBeInTheDocument()
    expect(screen.queryByText('Run a batch')).not.toBeInTheDocument()
  })
})
