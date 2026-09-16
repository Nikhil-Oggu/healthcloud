import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { CreateReprocessingBatchForm } from './CreateReprocessingBatchForm'
import type { CoveragePlan, ReprocessingBatch } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listCoveragePlans: vi.fn(),
      runReprocessingBatch: vi.fn(),
    },
  }
})

const listCoveragePlans = vi.mocked(api.listCoveragePlans)
const runReprocessingBatch = vi.mocked(api.runReprocessingBatch)

const PLANS: CoveragePlan[] = [
  { id: 'p1', planCode: 'PPO-1', name: 'North PPO', planType: 'PPO', deductibleAmount: 0,
    coinsuranceRate: 0.2, copayAmount: 0, outOfPocketMax: null, active: true, version: 0 },
]
const CREATED: ReprocessingBatch = {
  id: 'b-new', batchNumber: 'RPB-NEW00001', coveragePlanId: 'p1', coveragePlanName: 'North PPO',
  status: 'COMPLETED', totalCount: 1, succeededCount: 1, failedCount: 0,
  requestedBy: 'u1', createdAt: '2026-06-01T10:00:00Z', finishedAt: '2026-06-01T10:00:01Z', items: [],
}

function renderForm(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CreateReprocessingBatchForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listCoveragePlans.mockResolvedValue(PLANS)
  })

  it('runs a batch for the selected plan', async () => {
    runReprocessingBatch.mockResolvedValue(CREATED)
    renderForm(<CreateReprocessingBatchForm />)

    await screen.findByRole('option', { name: 'North PPO (PPO-1)' })

    await userEvent.selectOptions(screen.getByLabelText('Coverage plan'), 'p1')
    await userEvent.click(screen.getByRole('button', { name: 'Run batch' }))

    await waitFor(() => expect(runReprocessingBatch).toHaveBeenCalledTimes(1))
    expect(runReprocessingBatch.mock.calls[0][0]).toEqual({ coveragePlanId: 'p1' })
  })

  it('blocks submit when no plan is chosen', async () => {
    renderForm(<CreateReprocessingBatchForm />)
    await screen.findByRole('option', { name: 'North PPO (PPO-1)' })

    await userEvent.click(screen.getByRole('button', { name: 'Run batch' }))

    await waitFor(() => expect(screen.getByText('Required')).toBeInTheDocument())
    expect(runReprocessingBatch).not.toHaveBeenCalled()
  })
})
