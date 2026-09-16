import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { CreateClaimForm } from './CreateClaimForm'
import type { Claim, Patient, Provider } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listPatients: vi.fn(),
      listProviders: vi.fn(),
      createClaim: vi.fn(),
      searchMedicalCodes: vi.fn(),
    },
  }
})

const listPatients = vi.mocked(api.listPatients)
const listProviders = vi.mocked(api.listProviders)
const createClaim = vi.mocked(api.createClaim)
const searchMedicalCodes = vi.mocked(api.searchMedicalCodes)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const PROVIDERS: Provider[] = [
  { userId: 'prov-dana', fullName: 'Dana Provider' },
  { userId: 'prov-morgan', fullName: 'Morgan Provider' },
]
const CREATED: Claim = {
  id: 'new1', patientId: 'p1', claimNumber: 'CLM-NEW00001', status: 'DRAFT', serviceDate: '2026-01-10',
  totalChargeAmount: 150, renderingProviderId: null, createdBy: 'u1', createdAt: '2026-01-10T10:00:00Z',
  version: 0, lines: [],
}

function renderForm(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CreateClaimForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    listProviders.mockResolvedValue(PROVIDERS)
    searchMedicalCodes.mockResolvedValue([])
  })

  it('creates a claim with the entered patient, code and charge', async () => {
    createClaim.mockResolvedValue(CREATED)
    renderForm(<CreateClaimForm />)

    // Patient options load asynchronously.
    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })
    await userEvent.selectOptions(screen.getByLabelText('Patient'), 'p1')

    // freeSolo picker: typing a raw code sets the line's value without needing to select an option.
    await userEvent.type(screen.getByLabelText('Procedure code'), '99213')
    await userEvent.type(screen.getByLabelText('Charge'), '150')

    await userEvent.click(screen.getByRole('button', { name: 'Create claim' }))

    await waitFor(() => expect(createClaim).toHaveBeenCalledTimes(1))
    const body = createClaim.mock.calls[0][0]
    expect(body.patientId).toBe('p1')
    expect(body.lines).toHaveLength(1)
    expect(body.lines[0].procedureCode).toBe('99213')
    expect(body.lines[0].chargeAmount).toBe(150)
    // No provider chosen → the optional field is omitted.
    expect(body.renderingProviderId).toBeUndefined()
  })

  it('sends the chosen rendering provider', async () => {
    createClaim.mockResolvedValue(CREATED)
    renderForm(<CreateClaimForm />)

    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })
    await userEvent.selectOptions(screen.getByLabelText('Patient'), 'p1')

    // The provider options load asynchronously; pick Morgan (the out-of-network provider in the demo).
    await screen.findByRole('option', { name: 'Morgan Provider' })
    await userEvent.selectOptions(screen.getByLabelText('Rendering provider'), 'prov-morgan')

    await userEvent.type(screen.getByLabelText('Procedure code'), '99213')
    await userEvent.type(screen.getByLabelText('Charge'), '150')
    await userEvent.click(screen.getByRole('button', { name: 'Create claim' }))

    await waitFor(() => expect(createClaim).toHaveBeenCalledTimes(1))
    expect(createClaim.mock.calls[0][0].renderingProviderId).toBe('prov-morgan')
  })

  it('blocks submit when no patient is selected', async () => {
    renderForm(<CreateClaimForm />)
    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })

    await userEvent.type(screen.getByLabelText('Procedure code'), '99213')
    await userEvent.type(screen.getByLabelText('Charge'), '150')
    await userEvent.click(screen.getByRole('button', { name: 'Create claim' }))

    // Zod validation stops the submit — the API is never called and the patient error shows.
    await screen.findByText('Required')
    expect(createClaim).not.toHaveBeenCalled()
  })
})
