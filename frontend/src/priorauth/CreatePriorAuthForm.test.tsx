import type { ReactNode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { CreatePriorAuthForm } from './CreatePriorAuthForm'
import type { CoveragePlan, Patient, PriorAuthorization } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listPatients: vi.fn(),
      listCoveragePlans: vi.fn(),
      searchMedicalCodes: vi.fn(),
      createPriorAuthorization: vi.fn(),
    },
  }
})

const listPatients = vi.mocked(api.listPatients)
const listCoveragePlans = vi.mocked(api.listCoveragePlans)
const searchMedicalCodes = vi.mocked(api.searchMedicalCodes)
const createPriorAuthorization = vi.mocked(api.createPriorAuthorization)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const PLANS: CoveragePlan[] = [
  { id: 'pl1', planCode: 'NC-PPO-STD', name: 'Standard PPO', planType: 'PPO', deductibleAmount: 1500,
    coinsuranceRate: 0.2, copayAmount: 25, outOfPocketMax: 6000, active: true, version: 0 },
]
const CREATED: PriorAuthorization = {
  id: 'pa-new', patientId: 'p1', authNumber: 'PA-NEW00001', coveragePlanId: 'pl1', coveragePlanName: 'Standard PPO',
  procedureCodeSystem: 'CPT', procedureCode: '99214', requestedServiceFrom: '2026-07-01', requestedServiceTo: null,
  status: 'REQUESTED', decisionReason: null, decidedBy: null, decidedAt: null, requestedBy: 'u1',
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

describe('CreatePriorAuthForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    listCoveragePlans.mockResolvedValue(PLANS)
    searchMedicalCodes.mockResolvedValue([])
  })

  it('requests a prior authorization with the entered patient, plan, procedure and date', async () => {
    createPriorAuthorization.mockResolvedValue(CREATED)
    renderForm(<CreatePriorAuthForm />)

    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })
    await userEvent.selectOptions(screen.getByLabelText('Patient'), 'p1')
    await userEvent.selectOptions(screen.getByLabelText('Coverage plan'), 'pl1')
    await userEvent.type(screen.getByLabelText('Procedure code'), '99214')
    // Native date input: set the value directly rather than typing.
    fireEvent.change(screen.getByLabelText('Service from'), { target: { value: '2026-07-01' } })

    await userEvent.click(screen.getByRole('button', { name: 'Request' }))

    await waitFor(() => expect(createPriorAuthorization).toHaveBeenCalledTimes(1))
    const body = createPriorAuthorization.mock.calls[0][0]
    expect(body.patientId).toBe('p1')
    expect(body.coveragePlanId).toBe('pl1')
    expect(body.procedureCode).toBe('99214')
    expect(body.requestedServiceFrom).toBe('2026-07-01')
    expect(body.requestedServiceTo).toBeUndefined()
  })

  it('blocks submit when required fields are missing', async () => {
    renderForm(<CreatePriorAuthForm />)
    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })

    // Nothing selected/entered → Zod stops the submit and the API is never called.
    await userEvent.click(screen.getByRole('button', { name: 'Request' }))

    await waitFor(() => expect(screen.getAllByText('Required').length).toBeGreaterThan(0))
    expect(createPriorAuthorization).not.toHaveBeenCalled()
  })
})
