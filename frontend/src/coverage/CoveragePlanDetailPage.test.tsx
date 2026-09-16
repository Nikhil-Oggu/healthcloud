import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { CoveragePlanDetailPage } from './CoveragePlanDetailPage'
import type {
  CoveragePlan,
  CurrentUser,
  PlanExclusion,
  PlanFeeScheduleEntry,
  PlanPriorAuthRequirement,
} from '../api/types'

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
      listFeeSchedule: vi.fn(),
      addFeeSchedule: vi.fn(),
      removeFeeSchedule: vi.fn(),
      listPriorAuthRequirements: vi.fn(),
      addPriorAuthRequirement: vi.fn(),
      removePriorAuthRequirement: vi.fn(),
      searchMedicalCodes: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getCoveragePlan = vi.mocked(api.getCoveragePlan)
const listExclusions = vi.mocked(api.listExclusions)
const addExclusion = vi.mocked(api.addExclusion)
const removeExclusion = vi.mocked(api.removeExclusion)
const listFeeSchedule = vi.mocked(api.listFeeSchedule)
const addFeeSchedule = vi.mocked(api.addFeeSchedule)
const removeFeeSchedule = vi.mocked(api.removeFeeSchedule)
const listPriorAuthRequirements = vi.mocked(api.listPriorAuthRequirements)
const addPriorAuthRequirement = vi.mocked(api.addPriorAuthRequirement)
const removePriorAuthRequirement = vi.mocked(api.removePriorAuthRequirement)
const searchMedicalCodes = vi.mocked(api.searchMedicalCodes)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PLAN: CoveragePlan = {
  id: 'pl1', planCode: 'PPO-STD', name: 'Standard PPO', planType: 'PPO', deductibleAmount: 1500,
  coinsuranceRate: 0.2, copayAmount: 25, outOfPocketMax: 6000, active: true, version: 0,
}
const EXCLUSIONS: PlanExclusion[] = [
  { id: 'ex1', coveragePlanId: 'pl1', codeSystem: 'CPT', code: '80053' },
]
const FEE_SCHEDULE: PlanFeeScheduleEntry[] = [
  { id: 'fs1', coveragePlanId: 'pl1', codeSystem: 'CPT', code: '99213', allowedAmount: 110 },
]
const PRIOR_AUTH_REQUIREMENTS: PlanPriorAuthRequirement[] = [
  { id: 'pa1', coveragePlanId: 'pl1', codeSystem: 'CPT', code: '99214' },
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
    listExclusions.mockResolvedValue([])
    listFeeSchedule.mockResolvedValue([])
    listPriorAuthRequirements.mockResolvedValue([])
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

  it('renders a fee-schedule entry with its allowed amount', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listFeeSchedule.mockResolvedValue(FEE_SCHEDULE)

    renderDetail(<CoveragePlanDetailPage />)

    expect(await screen.findByText(/Standard PPO/)).toBeInTheDocument()
    // The fee-schedule list loads from its own query.
    expect(await screen.findByText('$110.00')).toBeInTheDocument()
    // A non-admin sees no add/remove controls.
    expect(screen.queryByLabelText('Price a procedure')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add entry' })).not.toBeInTheDocument()
  })

  it('an admin can add a fee-schedule entry via the picker and amount', async () => {
    mockUser(['ORG_ADMIN'])
    listFeeSchedule.mockResolvedValue([])
    addFeeSchedule.mockResolvedValue({
      id: 'fs2', coveragePlanId: 'pl1', codeSystem: 'CPT', code: '99213', allowedAmount: 90,
    })

    renderDetail(<CoveragePlanDetailPage />)
    await screen.findByText(/Standard PPO/)

    await userEvent.type(screen.getByLabelText('Price a procedure'), '99213')
    await userEvent.type(screen.getByLabelText('Allowed amount'), '90.00')
    await userEvent.click(screen.getByRole('button', { name: 'Add entry' }))

    await waitFor(() =>
      expect(addFeeSchedule).toHaveBeenCalledWith('pl1', { procedureCode: '99213', allowedAmount: 90 }))
  })

  it('an admin can remove a fee-schedule entry', async () => {
    mockUser(['ORG_ADMIN'])
    listFeeSchedule.mockResolvedValue(FEE_SCHEDULE)
    removeFeeSchedule.mockResolvedValue(undefined)

    renderDetail(<CoveragePlanDetailPage />)
    await screen.findByText('$110.00')

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(removeFeeSchedule).toHaveBeenCalledWith('pl1', 'fs1'))
  })

  it('renders a prior-auth requirement', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    listPriorAuthRequirements.mockResolvedValue(PRIOR_AUTH_REQUIREMENTS)

    renderDetail(<CoveragePlanDetailPage />)

    expect(await screen.findByText(/Standard PPO/)).toBeInTheDocument()
    // The prior-auth requirement list loads from its own query.
    expect(await screen.findByText('99214')).toBeInTheDocument()
    // A non-admin sees no add control.
    expect(screen.queryByLabelText('Require prior auth for a procedure')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add requirement' })).not.toBeInTheDocument()
  })

  it('an admin can add a prior-auth requirement via the picker', async () => {
    mockUser(['ORG_ADMIN'])
    addPriorAuthRequirement.mockResolvedValue({ id: 'pa2', coveragePlanId: 'pl1', codeSystem: 'CPT', code: '99214' })

    renderDetail(<CoveragePlanDetailPage />)
    await screen.findByText(/Standard PPO/)

    await userEvent.type(screen.getByLabelText('Require prior auth for a procedure'), '99214')
    await userEvent.click(screen.getByRole('button', { name: 'Add requirement' }))

    await waitFor(() =>
      expect(addPriorAuthRequirement).toHaveBeenCalledWith('pl1', { procedureCode: '99214' }))
  })

  it('an admin can remove a prior-auth requirement', async () => {
    mockUser(['ORG_ADMIN'])
    listPriorAuthRequirements.mockResolvedValue(PRIOR_AUTH_REQUIREMENTS)
    removePriorAuthRequirement.mockResolvedValue(undefined)

    renderDetail(<CoveragePlanDetailPage />)
    await screen.findByText('99214')

    await userEvent.click(screen.getByRole('button', { name: 'Remove' }))

    await waitFor(() => expect(removePriorAuthRequirement).toHaveBeenCalledWith('pl1', 'pa1'))
  })
})
