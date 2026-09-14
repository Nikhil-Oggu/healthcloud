import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { PatientsPage } from './PatientsPage'
import type { CurrentUser, Patient } from '../api/types'

// Mock the api surface and the current-user hook; keep everything else real.
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { ...actual.api, listPatients: vi.fn(), createPatient: vi.fn() },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listPatients = vi.mocked(api.listPatients)
const createPatient = vi.mocked(api.createPatient)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENT: Patient = {
  id: 'p1',
  medicalRecordNumber: 'NC-0001',
  fullName: 'Sam Sample',
  dateOfBirth: '1985-03-14',
  status: 'ACTIVE',
  version: 0,
}

function mockUserWithRoles(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: {
      userId: 'u1',
      email: 'x@northcare.example.org',
      fullName: 'X',
      organizationId: 'o1',
      organizationName: 'NorthCare Health',
      roles,
    } as CurrentUser,
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

describe('PatientsPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the tenant patient list', async () => {
    mockUserWithRoles(['PROVIDER'])
    listPatients.mockResolvedValue([PATIENT])

    renderPage(<PatientsPage />)

    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('NC-0001')).toBeInTheDocument()
  })

  it('shows "Restricted" when the date of birth is masked by consent', async () => {
    mockUserWithRoles(['PROVIDER'])
    listPatients.mockResolvedValue([
      { ...PATIENT, dateOfBirth: null, maskedFields: ['dateOfBirth'] },
    ])

    renderPage(<PatientsPage />)

    await screen.findByText('Sam Sample')
    expect(screen.getByText('Restricted')).toBeInTheDocument()
    expect(screen.queryByText('1985-03-14')).not.toBeInTheDocument()
  })

  it('hides the add form for non-write roles', async () => {
    mockUserWithRoles(['PROVIDER'])
    listPatients.mockResolvedValue([PATIENT])

    renderPage(<PatientsPage />)

    await screen.findByText('Sam Sample')
    expect(screen.queryByText('Add patient')).not.toBeInTheDocument()
  })

  it('lets a coordinator create a patient', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    listPatients.mockResolvedValue([])
    createPatient.mockResolvedValue({ ...PATIENT, id: 'p2', medicalRecordNumber: 'NC-0009' })

    renderPage(<PatientsPage />)
    expect(await screen.findByText('Add patient')).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText('Full name'), 'New Patient')
    await userEvent.type(screen.getByLabelText('MRN'), 'NC-0009')
    await userEvent.type(screen.getByLabelText('Date of birth'), '1990-01-01')
    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    await waitFor(() =>
      expect(createPatient).toHaveBeenCalledWith({
        fullName: 'New Patient',
        medicalRecordNumber: 'NC-0009',
        dateOfBirth: '1990-01-01',
      }),
    )
  })

  it('shows validation errors and does not submit an empty form', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    listPatients.mockResolvedValue([])

    renderPage(<PatientsPage />)
    await screen.findByText('Add patient')

    await userEvent.click(screen.getByRole('button', { name: 'Add' }))

    expect(await screen.findAllByText('Required')).not.toHaveLength(0)
    expect(createPatient).not.toHaveBeenCalled()
  })
})
