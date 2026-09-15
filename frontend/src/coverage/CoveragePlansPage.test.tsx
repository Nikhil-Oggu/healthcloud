import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { CoveragePlansPage } from './CoveragePlansPage'
import type { CoveragePlan, CurrentUser } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, listCoveragePlans: vi.fn(), createCoveragePlan: vi.fn() } }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listCoveragePlans = vi.mocked(api.listCoveragePlans)
const createCoveragePlan = vi.mocked(api.createCoveragePlan)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PLANS: CoveragePlan[] = [
  { id: 'pl1', planCode: 'PPO-STD', name: 'Standard PPO', planType: 'PPO', deductibleAmount: 1500,
    coinsuranceRate: 0.2, copayAmount: 25, outOfPocketMax: 6000, active: true, version: 0 },
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

describe('CoveragePlansPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists plans with the coinsurance as a percentage', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listCoveragePlans.mockResolvedValue(PLANS)

    renderPage(<CoveragePlansPage />)

    expect(await screen.findByText('PPO-STD')).toBeInTheDocument()
    expect(screen.getByText('20%')).toBeInTheDocument()
    // A non-admin sees no create form.
    expect(screen.queryByText('New coverage plan')).not.toBeInTheDocument()
  })

  it('an admin can create a plan', async () => {
    mockUser(['ORG_ADMIN'])
    listCoveragePlans.mockResolvedValue([])
    createCoveragePlan.mockResolvedValue(PLANS[0])

    renderPage(<CoveragePlansPage />)
    await screen.findByText('New coverage plan')

    await userEvent.type(screen.getByLabelText('Plan code'), 'EPO-1')
    await userEvent.type(screen.getByLabelText('Name'), 'Basic EPO')
    await userEvent.click(screen.getByRole('button', { name: 'Create plan' }))

    await waitFor(() => expect(createCoveragePlan).toHaveBeenCalledTimes(1))
    const body = createCoveragePlan.mock.calls[0][0]
    expect(body.planCode).toBe('EPO-1')
    expect(body.name).toBe('Basic EPO')
    expect(body.planType).toBe('PPO') // the default
  })
})
