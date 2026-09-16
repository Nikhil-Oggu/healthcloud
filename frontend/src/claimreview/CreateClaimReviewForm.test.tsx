import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { CreateClaimReviewForm } from './CreateClaimReviewForm'
import type { ClaimReview, ClaimSummary } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listClaims: vi.fn(),
      createClaimReview: vi.fn(),
    },
  }
})

const listClaims = vi.mocked(api.listClaims)
const createClaimReview = vi.mocked(api.createClaimReview)

const CLAIMS: ClaimSummary[] = [
  { id: 'c1', patientId: 'p1', claimNumber: 'CLM-ONE', status: 'ADJUDICATED', serviceDate: '2025-11-01',
    totalChargeAmount: 150, renderingProviderId: null, createdAt: '2026-05-01T10:00:00Z' },
]
const CREATED: ClaimReview = {
  id: 'r-new', claimId: 'c1', patientId: 'p1', reviewNumber: 'MRV-NEW00001',
  reason: 'Please check', status: 'OPEN', resolution: null, openedBy: 'u1', resolvedBy: null, resolvedAt: null,
  createdAt: '2026-06-01T10:00:00Z', version: 0,
}

function renderForm(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CreateClaimReviewForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listClaims.mockResolvedValue(CLAIMS)
  })

  it('opens a review for the selected claim and reason', async () => {
    createClaimReview.mockResolvedValue(CREATED)
    renderForm(<CreateClaimReviewForm />)

    await screen.findByRole('option', { name: 'CLM-ONE (ADJUDICATED)' })

    await userEvent.selectOptions(screen.getByLabelText('Claim'), 'c1')
    await userEvent.type(screen.getByLabelText('Reason'), 'Please check')
    await userEvent.click(screen.getByRole('button', { name: 'Open review' }))

    await waitFor(() => expect(createClaimReview).toHaveBeenCalledTimes(1))
    const body = createClaimReview.mock.calls[0][0]
    expect(body.claimId).toBe('c1')
    expect(body.reason).toBe('Please check')
  })

  it('blocks submit when no claim is chosen', async () => {
    renderForm(<CreateClaimReviewForm />)
    await screen.findByRole('option', { name: 'CLM-ONE (ADJUDICATED)' })

    await userEvent.click(screen.getByRole('button', { name: 'Open review' }))

    await waitFor(() => expect(screen.getByText('Required')).toBeInTheDocument())
    expect(createClaimReview).not.toHaveBeenCalled()
  })
})
