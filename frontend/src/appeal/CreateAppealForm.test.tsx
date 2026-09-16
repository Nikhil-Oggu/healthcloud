import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { CreateAppealForm } from './CreateAppealForm'
import type { Appeal, ClaimSummary } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listClaims: vi.fn(),
      createAppeal: vi.fn(),
    },
  }
})

const listClaims = vi.mocked(api.listClaims)
const createAppeal = vi.mocked(api.createAppeal)

const CLAIMS: ClaimSummary[] = [
  { id: 'c1', patientId: 'p1', claimNumber: 'CLM-REJECT', status: 'REJECTED', serviceDate: '2025-11-01',
    totalChargeAmount: 150, createdAt: '2026-05-01T10:00:00Z' },
  // A DRAFT claim is not appealable — it must not appear in the picker.
  { id: 'c2', patientId: 'p1', claimNumber: 'CLM-DRAFT', status: 'DRAFT', serviceDate: '2025-11-01',
    totalChargeAmount: 90, createdAt: '2026-05-01T10:00:00Z' },
]
const CREATED: Appeal = {
  id: 'a-new', claimId: 'c1', patientId: 'p1', appealNumber: 'APL-NEW00001',
  reason: 'Please reconsider', status: 'SUBMITTED', decisionReason: null, decidedBy: null, decidedAt: null,
  submittedBy: 'u1', createdAt: '2026-06-01T10:00:00Z', version: 0,
}

function renderForm(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CreateAppealForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listClaims.mockResolvedValue(CLAIMS)
  })

  it('submits an appeal for the selected claim and reason (only appealable claims are offered)', async () => {
    createAppeal.mockResolvedValue(CREATED)
    renderForm(<CreateAppealForm />)

    // Only the REJECTED claim is an option; the DRAFT one is filtered out.
    await screen.findByRole('option', { name: 'CLM-REJECT (REJECTED)' })
    expect(screen.queryByRole('option', { name: 'CLM-DRAFT (DRAFT)' })).not.toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Claim'), 'c1')
    await userEvent.type(screen.getByLabelText('Reason'), 'Please reconsider')
    await userEvent.click(screen.getByRole('button', { name: 'Submit' }))

    await waitFor(() => expect(createAppeal).toHaveBeenCalledTimes(1))
    const body = createAppeal.mock.calls[0][0]
    expect(body.claimId).toBe('c1')
    expect(body.reason).toBe('Please reconsider')
  })

  it('blocks submit when required fields are missing', async () => {
    renderForm(<CreateAppealForm />)
    await screen.findByRole('option', { name: 'CLM-REJECT (REJECTED)' })

    await userEvent.click(screen.getByRole('button', { name: 'Submit' }))

    await waitFor(() => expect(screen.getAllByText('Required').length).toBeGreaterThan(0))
    expect(createAppeal).not.toHaveBeenCalled()
  })
})
