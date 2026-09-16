import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { PriorAuthorizationsPage } from './PriorAuthorizationsPage'
import type { Patient, PriorAuthorizationSummary } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, listPriorAuthorizations: vi.fn(), listPatients: vi.fn() } }
})

const listPriorAuthorizations = vi.mocked(api.listPriorAuthorizations)
const listPatients = vi.mocked(api.listPatients)

const PATIENTS: Patient[] = [
  { id: 'p1', medicalRecordNumber: 'NC-0001', fullName: 'Sam Sample', dateOfBirth: null, status: 'ACTIVE', version: 0 },
]
const AUTHS: PriorAuthorizationSummary[] = [
  { id: 'pa1', patientId: 'p1', authNumber: 'PA-ABC12345', procedureCodeSystem: 'CPT', procedureCode: '99214',
    status: 'REQUESTED', requestedServiceFrom: '2026-06-01', createdAt: '2026-05-01T10:00:00Z' },
]

function renderPage(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PriorAuthorizationsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listPatients.mockResolvedValue(PATIENTS)
  })

  it('lists prior authorizations with the patient name, procedure and status', async () => {
    listPriorAuthorizations.mockResolvedValue(AUTHS)

    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('PA-ABC12345')).toBeInTheDocument()
    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('99214')).toBeInTheDocument()
    expect(screen.getByText('REQUESTED')).toBeInTheDocument()
  })

  it('shows an empty state when there are none', async () => {
    listPriorAuthorizations.mockResolvedValue([])

    renderPage(<PriorAuthorizationsPage />)

    expect(await screen.findByText('No prior authorizations yet.')).toBeInTheDocument()
  })
})
