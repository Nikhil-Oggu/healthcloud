import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { PatientDetailPage } from './PatientDetailPage'
import type { ConsentDirective, CurrentUser, Patient } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getPatient: vi.fn(),
      listConsentDirectives: vi.fn(),
      listProviderAssignments: vi.fn(),
      recordConsent: vi.fn(),
      revokeConsent: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getPatient = vi.mocked(api.getPatient)
const listConsentDirectives = vi.mocked(api.listConsentDirectives)
const listProviderAssignments = vi.mocked(api.listProviderAssignments)
const recordConsent = vi.mocked(api.recordConsent)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PATIENT: Patient = {
  id: 'p1',
  medicalRecordNumber: 'NC-0001',
  fullName: 'Sam Sample',
  dateOfBirth: '1985-03-14',
  status: 'ACTIVE',
  version: 0,
}

const DIRECTIVE: ConsentDirective = {
  id: 'd1',
  patientId: 'p1',
  directiveGroupId: 'g1',
  effect: 'GRANT',
  purpose: 'CARE_COORDINATION',
  dataCategory: 'DEMOGRAPHICS_CONTACT',
  scopeType: 'ORGANIZATION',
  scopeRefId: null,
  effectiveFrom: '2026-02-02',
  effectiveTo: null,
  status: 'ACTIVE',
  version: 1,
  expectedVersion: 0,
  createdAt: '2026-02-02T00:00:00Z',
  endedAt: null,
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
      <MemoryRouter initialEntries={['/patients/p1']}>
        <Routes>
          <Route path="/patients/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('PatientDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listProviderAssignments.mockResolvedValue([])
  })

  it('renders the patient summary and a consent directive row', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([DIRECTIVE])

    renderPage(<PatientDetailPage />)

    expect(await screen.findByText('Sam Sample')).toBeInTheDocument()
    expect(screen.getByText('NC-0001')).toBeInTheDocument()
    expect(screen.getByText('2026-02-02')).toBeInTheDocument() // the directive's effective date
    expect(screen.getByRole('button', { name: 'Revoke' })).toBeInTheDocument()
  })

  it('shows "Restricted" when the date of birth is masked', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue({ ...PATIENT, dateOfBirth: null, maskedFields: ['dateOfBirth'] })
    listConsentDirectives.mockResolvedValue([])

    renderPage(<PatientDetailPage />)

    await screen.findByText('Sam Sample')
    expect(screen.getByText('Restricted')).toBeInTheDocument()
    expect(screen.queryByText('1985-03-14')).not.toBeInTheDocument()
  })

  it('lets a coordinator record a directive', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    recordConsent.mockResolvedValue(DIRECTIVE)

    renderPage(<PatientDetailPage />)
    expect(await screen.findByText('Record directive')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Record' }))

    await waitFor(() =>
      expect(recordConsent).toHaveBeenCalledWith('p1', {
        effect: 'GRANT',
        purpose: 'CARE_COORDINATION',
        dataCategory: 'DEMOGRAPHICS_CONTACT',
        scopeType: 'ORGANIZATION',
        scopeRefId: undefined,
        effectiveFrom: undefined,
        effectiveTo: undefined,
      }),
    )
  })

  it('hides the record form and revoke actions for non-write roles', async () => {
    mockUserWithRoles(['PROVIDER'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([DIRECTIVE])

    renderPage(<PatientDetailPage />)

    await screen.findByText('Sam Sample')
    expect(screen.queryByText('Record directive')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
  })
})
