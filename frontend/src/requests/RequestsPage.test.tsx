import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { RequestsPage } from './RequestsPage'
import type { CurrentUser, Patient, ServiceRequest } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { ...actual.api, listRequests: vi.fn(), createRequest: vi.fn(), listPatients: vi.fn() },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listRequests = vi.mocked(api.listRequests)
const createRequest = vi.mocked(api.createRequest)
const listPatients = vi.mocked(api.listPatients)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENT: Patient = {
  id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample',
  dateOfBirth: '1985-03-14', status: 'ACTIVE', version: 0,
}
const REQUEST: ServiceRequest = {
  id: 'r1', patientId: 'p1', type: 'CLAIM_SUPPORT', status: 'DRAFT', priority: 'NORMAL',
  title: 'Help with a claim', description: null, createdBy: 'u1', version: 0, createdAt: '2026-09-13T10:00:00Z',
}

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

describe('RequestsPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the request list', async () => {
    mockUser(['PROVIDER'])
    listRequests.mockResolvedValue([REQUEST])
    listPatients.mockResolvedValue([PATIENT])

    renderPage(<RequestsPage />)

    expect(await screen.findByText('Help with a claim')).toBeInTheDocument()
    expect(screen.getByText('DRAFT')).toBeInTheDocument()
  })

  it('hides the create form for non-create roles', async () => {
    mockUser(['AUDITOR'])
    listRequests.mockResolvedValue([REQUEST])
    listPatients.mockResolvedValue([PATIENT])

    renderPage(<RequestsPage />)

    await screen.findByText('Help with a claim')
    expect(screen.queryByRole('button', { name: 'Create request' })).not.toBeInTheDocument()
  })

  it('creates a request from the form', async () => {
    mockUser(['CARE_COORDINATOR'])
    listRequests.mockResolvedValue([])
    listPatients.mockResolvedValue([PATIENT])
    createRequest.mockResolvedValue({ ...REQUEST, id: 'r2', title: 'New one' })

    renderPage(<RequestsPage />)
    expect(await screen.findByRole('button', { name: 'Create request' })).toBeInTheDocument()
    // Wait for the patient dropdown to populate before selecting.
    await screen.findByRole('option', { name: 'Sam Sample (NC-0001)' })

    await userEvent.selectOptions(screen.getByLabelText('Patient'), 'p1')
    await userEvent.type(screen.getByLabelText('Request title'), 'New one')
    await userEvent.click(screen.getByRole('button', { name: 'Create request' }))

    await waitFor(() =>
      expect(createRequest).toHaveBeenCalledWith(
        expect.objectContaining({ patientId: 'p1', type: 'CLAIM_SUPPORT', priority: 'NORMAL', title: 'New one' }),
      ),
    )
  })
})
