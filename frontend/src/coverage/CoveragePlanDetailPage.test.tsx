import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { CoveragePlanDetailPage } from './CoveragePlanDetailPage'
import type { CoveragePlan, CurrentUser, PlanExclusion } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getCoveragePlan: vi.fn(),
      listExclusions: vi.fn(),
      addExclusion: vi.fn(),
      removeExclusion: vi.fn(),
      searchMedicalCodes: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getCoveragePlan = vi.mocked(api.getCoveragePlan)
const listExclusions = vi.mocked(api.listExclusions)
const addExclusion = vi.mocked(api.addExclusion)
const removeExclusion = vi.mocked(api.removeExclusion)
const searchMedicalCodes = vi.mocked(api.searchMedicalCodes)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PLAN: CoveragePlan = {
  id: 'pl1', planCode: 'PPO-STD', name: 'Standard PPO', planType: 'PPO', deductibleAmount: 1500,
  coinsuranceRate: 0.2, copayAmount: 25, outOfPocketMax: 6000, active: true, version: 0,
}
const EXCLUSIONS: PlanExclusion[] = [
  { id: 'ex1', coveragePlanId: 'pl1', codeSystem: 'CPT', code: '80053' },
]

function mockUser(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: { userId: 'u1', email: 'x@northcare.example.org', fullName: 'X',
      organizationId: 'o1', organizationName: 'NorthCare Health', roles } as CurrentUser,
  } as ReturnType<typeof useCurrentUser>)
}

function renderDetail(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/coverage-plans/pl1']}>
        <Routes>
          <Route path="/coverage-plans/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CoveragePlanDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    getCoveragePlan.mockResolvedValue(PLAN)
    searchMedicalCodes.mockResolvedValue([])
  })

  it('renders the plan parameters and its exclusions', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listExclusions.mockResolvedValue(EXCLUSIONS)

    renderDetail(<CoveragePlanDetailPage />)

    expect(await screen.findByText(/Standard PPO/)).toBeInTheDocument()
    // The exclusions list loads from its own query.
    expect(await screen.findByText('80053')).toBeInTheDocument()
    // A non-admin sees no add control.
    expect(screen.queryByLabelText('Exclude a procedure')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove' })).not.toBeInTheDocument()
  })

  it('an admin can add an exclusion via the picker', async () => {
    mockUser(['ORG_ADMIN'])
    listExclusions.mockResolvedValue([])
    addExclusion.mockResolvedValue({ id: 'ex2', coveragePlanId: 'pl1', codeSystem: 'CPT', code: '99213' })

    renderDetail(<CoveragePlanDetailPage />)
    await screen.findByText(/Standard PPO/)

    await userEvent.type(screen.getByLabelText('Exclude a procedure'), '99213')
    await userEvent.click(screen.getByRole('button', { name: 'Add exclusion' }))

    await waitFor(() => expect(addExclusion).toHaveBeenCalledWith('pl1', { procedureCode: '99213' }))
  })

  it('an admin can remove an exclusion', async () => {
    mockUser(['ORG_ADMIN'])
    listExclusions.mockResolvedValue(EXCLUSIONS)
    removeExclusion.mockResolvedValue(undefined)

    renderDetail(<CoveragePlanDetailPage />)
    await screen.findByText('80053')

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(removeExclusion).toHaveBeenCalledWith('pl1', 'ex1'))
  })
})
