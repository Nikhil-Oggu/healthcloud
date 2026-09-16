import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { ReprocessingBatchDetailPage } from './ReprocessingBatchDetailPage'
import type { ClaimSummary, ReprocessingBatch } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getReprocessingBatch: vi.fn(),
      listClaims: vi.fn(),
    },
  }
})

const getReprocessingBatch = vi.mocked(api.getReprocessingBatch)
const listClaims = vi.mocked(api.listClaims)

const CLAIMS: ClaimSummary[] = [
  { id: 'c1', patientId: 'p1', claimNumber: 'CLM-ONE', status: 'ADJUDICATED', serviceDate: '2025-11-01',
    totalChargeAmount: 150, renderingProviderId: null, createdAt: '2026-05-01T10:00:00Z' },
]
const BATCH: ReprocessingBatch = {
  id: 'b1', batchNumber: 'RPB-ABC12345', coveragePlanId: 'p1', coveragePlanName: 'North PPO',
  status: 'COMPLETED_WITH_ERRORS', totalCount: 2, succeededCount: 1, failedCount: 1,
  requestedBy: 'u9', createdAt: '2026-06-01T10:00:00Z', finishedAt: '2026-06-01T10:00:05Z',
  items: [
    { id: 'i1', claimId: 'c1', outcome: 'SUCCEEDED', adjudicationVersion: 2, message: null,
      createdAt: '2026-06-01T10:00:01Z' },
    { id: 'i2', claimId: 'c2', outcome: 'FAILED', adjudicationVersion: null,
      message: 'Only an ACCEPTED or ADJUDICATED claim can be adjudicated.', createdAt: '2026-06-01T10:00:02Z' },
  ],
}

function renderDetail(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/reprocessing/b1']}>
        <Routes>
          <Route path="/reprocessing/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('ReprocessingBatchDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listClaims.mockResolvedValue(CLAIMS)
  })

  it('renders the header (plan + counts) and the per-claim items', async () => {
    getReprocessingBatch.mockResolvedValue(BATCH)

    renderDetail(<ReprocessingBatchDetailPage />)

    expect(await screen.findByText('Batch RPB-ABC12345')).toBeInTheDocument()
    expect(screen.getByText('Plan: North PPO')).toBeInTheDocument()
    expect(screen.getByText('1 succeeded · 1 failed · 2 total')).toBeInTheDocument()

    // A resolved claim number (c1 → CLM-ONE), the succeeded/failed outcomes, and the new version.
    expect(screen.getByText('CLM-ONE')).toBeInTheDocument()
    expect(screen.getByText('SUCCEEDED')).toBeInTheDocument()
    expect(screen.getByText('FAILED')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
    expect(
      screen.getByText('Only an ACCEPTED or ADJUDICATED claim can be adjudicated.'),
    ).toBeInTheDocument()
  })
})
