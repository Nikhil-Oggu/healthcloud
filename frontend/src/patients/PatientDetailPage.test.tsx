import type { ReactNode } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { PatientDetailPage } from './PatientDetailPage'
import type {
  ConsentDirective,
  CoordinatorAssignment,
  CoveragePlan,
  CurrentUser,
  Patient,
  PatientDocument,
  PatientEligibility,
  ProviderAssignment,
} from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getPatient: vi.fn(),
      listConsentDirectives: vi.fn(),
      listProviderAssignments: vi.fn(),
      listCoordinatorAssignments: vi.fn(),
      listProviderCandidates: vi.fn(),
      listCoordinatorCandidates: vi.fn(),
      recordConsent: vi.fn(),
      revokeConsent: vi.fn(),
      assignProvider: vi.fn(),
      revokeProviderAssignment: vi.fn(),
      assignCoordinator: vi.fn(),
      revokeCoordinatorAssignment: vi.fn(),
      listDocuments: vi.fn(),
      uploadDocument: vi.fn(),
      downloadDocument: vi.fn(),
      listEligibility: vi.fn(),
      enrollEligibility: vi.fn(),
      listCoveragePlans: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getPatient = vi.mocked(api.getPatient)
const listConsentDirectives = vi.mocked(api.listConsentDirectives)
const listProviderAssignments = vi.mocked(api.listProviderAssignments)
const listCoordinatorAssignments = vi.mocked(api.listCoordinatorAssignments)
const listProviderCandidates = vi.mocked(api.listProviderCandidates)
const listCoordinatorCandidates = vi.mocked(api.listCoordinatorCandidates)
const recordConsent = vi.mocked(api.recordConsent)
const assignProvider = vi.mocked(api.assignProvider)
const revokeProviderAssignment = vi.mocked(api.revokeProviderAssignment)
const listDocuments = vi.mocked(api.listDocuments)
const uploadDocument = vi.mocked(api.uploadDocument)
const listEligibility = vi.mocked(api.listEligibility)
const enrollEligibility = vi.mocked(api.enrollEligibility)
const listCoveragePlans = vi.mocked(api.listCoveragePlans)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const PROVIDER_ASSIGNMENT: ProviderAssignment = {
  id: 'pa1',
  patientId: 'p1',
  providerUserId: 'prov1',
  providerName: 'Dana Provider',
  assignedByUserId: 'u1',
  status: 'ACTIVE',
  effectiveFrom: '2026-02-02',
  effectiveTo: null,
  expectedVersion: 0,
  assignedAt: '2026-02-02T00:00:00Z',
  endedAt: null,
}

const COORDINATOR_ASSIGNMENT: CoordinatorAssignment = {
  id: 'ca1',
  patientId: 'p1',
  coordinatorUserId: 'coord1',
  coordinatorName: 'Cory Coordinator',
  assignedByUserId: 'u1',
  status: 'ACTIVE',
  effectiveFrom: '2026-02-02',
  effectiveTo: null,
  expectedVersion: 0,
  assignedAt: '2026-02-02T00:00:00Z',
  endedAt: null,
}

const PATIENT: Patient = {
  id: 'p1',
  medicalRecordNumber: 'NC-0001',
  fullName: 'Sam Sample',
  dateOfBirth: '1985-03-14',
  status: 'ACTIVE',
  version: 0,
}

const CLEAN_DOC: PatientDocument = {
  id: 'doc1',
  patientId: 'p1',
  fileName: 'summary.txt',
  contentType: 'text/plain',
  sizeBytes: 42,
  scanStatus: 'CLEAN',
  uploadedByUserId: 'u1',
  uploadedAt: '2026-02-02T00:00:00Z',
}

const QUARANTINED_DOC: PatientDocument = {
  ...CLEAN_DOC,
  id: 'doc2',
  fileName: 'infected.txt',
  scanStatus: 'QUARANTINED',
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

const COVERAGE_PLAN: CoveragePlan = {
  id: 'pl1',
  planCode: 'NC-PPO-STD',
  name: 'Standard PPO',
  planType: 'PPO',
  deductibleAmount: 1500,
  coinsuranceRate: 0.2,
  copayAmount: 25,
  outOfPocketMax: 6000,
  active: true,
  version: 0,
}

const ELIGIBILITY: PatientEligibility = {
  id: 'el1',
  patientId: 'p1',
  coveragePlanId: 'pl1',
  coveragePlanName: 'Standard PPO',
  memberId: 'MBR-123',
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
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
    listCoordinatorAssignments.mockResolvedValue([])
    listProviderCandidates.mockResolvedValue([])
    listCoordinatorCandidates.mockResolvedValue([])
    listDocuments.mockResolvedValue([])
    listEligibility.mockResolvedValue([])
    listCoveragePlans.mockResolvedValue([])
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

  it('lets a PATIENT manage consent on their own record but not the care team', async () => {
    mockUserWithRoles(['PATIENT'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([DIRECTIVE])
    listProviderAssignments.mockResolvedValue([PROVIDER_ASSIGNMENT])

    renderPage(<PatientDetailPage />)

    // Consent self-service: the patient sees the record form and a revoke action.
    expect(await screen.findByText('Record directive')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Revoke' })).toBeInTheDocument()

    // Care team stays staff-only: the member is visible (read) but there are no assign/revoke controls.
    expect(await screen.findByText('Dana Provider')).toBeInTheDocument()
    expect(screen.queryByLabelText('Add provider')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign' })).not.toBeInTheDocument()
  })

  it('renders the care team members', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listProviderAssignments.mockResolvedValue([PROVIDER_ASSIGNMENT])
    listCoordinatorAssignments.mockResolvedValue([COORDINATOR_ASSIGNMENT])

    renderPage(<PatientDetailPage />)

    expect(await screen.findByText('Care team')).toBeInTheDocument()
    expect(await screen.findByText('Dana Provider')).toBeInTheDocument()
    expect(await screen.findByText('Cory Coordinator')).toBeInTheDocument()
  })

  it('lets a coordinator assign a provider from the candidate list', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listProviderCandidates.mockResolvedValue([{ userId: 'prov2', fullName: 'New Provider' }])
    assignProvider.mockResolvedValue(PROVIDER_ASSIGNMENT)

    renderPage(<PatientDetailPage />)
    await screen.findByText('Care team')
    await screen.findByRole('option', { name: 'New Provider' }) // candidate list has loaded

    await userEvent.selectOptions(screen.getByLabelText('Add provider'), 'prov2')
    const enabledAssign = screen
      .getAllByRole('button', { name: 'Assign' })
      .find((b) => !b.hasAttribute('disabled'))!
    await userEvent.click(enabledAssign)

    await waitFor(() =>
      expect(assignProvider).toHaveBeenCalledWith('p1', {
        userId: 'prov2',
        effectiveFrom: undefined,
        effectiveTo: undefined,
      }),
    )
  })

  it('lets a coordinator revoke a provider assignment', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listProviderAssignments.mockResolvedValue([PROVIDER_ASSIGNMENT])
    revokeProviderAssignment.mockResolvedValue({ ...PROVIDER_ASSIGNMENT, status: 'REVOKED' })

    renderPage(<PatientDetailPage />)
    await screen.findByText('Dana Provider')

    await userEvent.click(screen.getByRole('button', { name: 'Revoke' }))

    await waitFor(() => expect(revokeProviderAssignment).toHaveBeenCalledWith('p1', 'pa1', 0))
  })

  it('hides the care-team assign/revoke controls for non-write roles', async () => {
    mockUserWithRoles(['PROVIDER'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listProviderAssignments.mockResolvedValue([PROVIDER_ASSIGNMENT])

    renderPage(<PatientDetailPage />)

    await screen.findByText('Dana Provider') // the member is still visible (read-only)
    expect(screen.queryByLabelText('Add provider')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
  })

  it('renders a clean document with a download button', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listDocuments.mockResolvedValue([CLEAN_DOC])

    renderPage(<PatientDetailPage />)

    expect(await screen.findByText('summary.txt')).toBeInTheDocument()
    expect(screen.getByText('CLEAN')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download' })).toBeInTheDocument()
  })

  it('shows a quarantined document with no download', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listDocuments.mockResolvedValue([QUARANTINED_DOC])

    renderPage(<PatientDetailPage />)

    expect(await screen.findByText('infected.txt')).toBeInTheDocument()
    expect(screen.getByText('QUARANTINED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Download' })).not.toBeInTheDocument()
    expect(screen.getByText('Quarantined')).toBeInTheDocument()
  })

  it('lets a coordinator upload a document', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listDocuments.mockResolvedValue([])
    uploadDocument.mockResolvedValue(CLEAN_DOC)

    renderPage(<PatientDetailPage />)
    await screen.findByText('Sam Sample')

    const file = new File(['hello'], 'summary.txt', { type: 'text/plain' })
    await userEvent.upload(screen.getByLabelText('Choose a document'), file)
    await userEvent.click(screen.getByRole('button', { name: 'Upload' }))

    await waitFor(() => expect(uploadDocument).toHaveBeenCalledWith('p1', file))
  })

  it('hides the document upload control for non-write roles', async () => {
    mockUserWithRoles(['PROVIDER'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listDocuments.mockResolvedValue([CLEAN_DOC])
    listProviderAssignments.mockResolvedValue([PROVIDER_ASSIGNMENT])

    renderPage(<PatientDetailPage />)

    await screen.findByText('summary.txt') // the list is visible read-only
    expect(screen.queryByLabelText('Choose a document')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Upload' })).not.toBeInTheDocument()
  })

  it('renders a coverage eligibility row', async () => {
    mockUserWithRoles(['CLAIMS_REVIEWER'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listEligibility.mockResolvedValue([ELIGIBILITY])

    renderPage(<PatientDetailPage />)

    expect(await screen.findByText('Coverage eligibility')).toBeInTheDocument()
    // The plan name loads from its own query.
    expect(await screen.findByText('Standard PPO')).toBeInTheDocument()
    expect(screen.getByText('MBR-123')).toBeInTheDocument()
    expect(screen.getByText('Open-ended')).toBeInTheDocument()
    // A non-write role sees no enroll form.
    expect(screen.queryByText('Enroll in a plan')).not.toBeInTheDocument()
  })

  it('lets a coordinator enroll the patient in a plan', async () => {
    mockUserWithRoles(['CARE_COORDINATOR'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listEligibility.mockResolvedValue([])
    listCoveragePlans.mockResolvedValue([COVERAGE_PLAN])
    enrollEligibility.mockResolvedValue(ELIGIBILITY)

    renderPage(<PatientDetailPage />)
    await screen.findByText('Enroll in a plan')
    await screen.findByRole('option', { name: 'Standard PPO (NC-PPO-STD)' }) // plans loaded

    await userEvent.selectOptions(screen.getByLabelText('Plan'), 'pl1')
    await userEvent.type(screen.getByLabelText('Member ID'), 'MBR-123')
    // Native date input: set the ISO value directly rather than typing it.
    fireEvent.change(screen.getByLabelText('Coverage start'), { target: { value: '2026-01-01' } })
    await userEvent.click(screen.getByRole('button', { name: 'Enroll' }))

    await waitFor(() =>
      expect(enrollEligibility).toHaveBeenCalledWith('p1', {
        coveragePlanId: 'pl1',
        memberId: 'MBR-123',
        effectiveFrom: '2026-01-01',
        effectiveTo: undefined,
      }),
    )
  })

  it('hides the enroll form for non-write roles', async () => {
    mockUserWithRoles(['PROVIDER'])
    getPatient.mockResolvedValue(PATIENT)
    listConsentDirectives.mockResolvedValue([])
    listEligibility.mockResolvedValue([ELIGIBILITY])

    renderPage(<PatientDetailPage />)

    await screen.findByText('Standard PPO') // the row is visible read-only
    expect(screen.queryByText('Enroll in a plan')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Enroll' })).not.toBeInTheDocument()
  })
})
