import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { CreateReferralForm } from './CreateReferralForm'
import type { Patient, Referral } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listPatients: vi.fn(),
      searchMedicalCodes: vi.fn(),
      createReferral: vi.fn(),
    },
  }
})

const listPatients = vi.mocked(api.listPatients)
const searchMedicalCodes = vi.mocked(api.searchMedicalCodes)
const createReferral = vi.mocked(api.createReferral)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const CREATED: Referral = {
  id: 'r-new', patientId: 'p1', referralNumber: 'REF-NEW00001', specialty: 'Cardiology',
  reasonCodeSystem: 'ICD10CM', reasonCode: 'I10', status: 'REQUESTED', decisionReason: null,
  decidedBy: null, decidedAt: null, requestedBy: 'u1', createdAt: '2026-06-01T10:00:00Z', version: 0,
}

function renderForm(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('CreateReferralForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
    searchMedicalCodes.mockResolvedValue([])
  })

  it('requests a referral with the entered patient, specialty and reason', async () => {
    createReferral.mockResolvedValue(CREATED)
    renderForm(<CreateReferralForm />)

    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })
    await userEvent.selectOptions(screen.getByLabelText('Patient'), 'p1')
    await userEvent.type(screen.getByLabelText('Specialty'), 'Cardiology')
    await userEvent.type(screen.getByLabelText('Reason (diagnosis)'), 'I10')

    await userEvent.click(screen.getByRole('button', { name: 'Request' }))

    await waitFor(() => expect(createReferral).toHaveBeenCalledTimes(1))
    const body = createReferral.mock.calls[0][0]
    expect(body.patientId).toBe('p1')
    expect(body.specialty).toBe('Cardiology')
    expect(body.reasonCode).toBe('I10')
  })

  it('blocks submit when required fields are missing', async () => {
    renderForm(<CreateReferralForm />)
    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })

    // Nothing selected/entered → Zod stops the submit and the API is never called.
    await userEvent.click(screen.getByRole('button', { name: 'Request' }))

    await waitFor(() => expect(screen.getAllByText('Required').length).toBeGreaterThan(0))
    expect(createReferral).not.toHaveBeenCalled()
  })
})
