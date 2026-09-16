import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { AuditEventsPage } from './AuditEventsPage'
import type { AuditChainVerification, AuditEvent } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listAuditEvents: vi.fn(),
      verifyAuditChain: vi.fn(),
    },
  }
})

const listAuditEvents = vi.mocked(api.listAuditEvents)
const verifyAuditChain = vi.mocked(api.verifyAuditChain)

const EVENTS: AuditEvent[] = [
  {
    id: 'e2', occurredAt: '2026-09-16T12:05:00Z', actorUserId: 'aaaaaaaa-0000-0000-0000-000000000001',
    action: 'CONSENT_REVOKED', resourceType: 'CONSENT_DIRECTIVE', resourceId: 'dddddddd-0000-0000-0000-000000000002',
    outcome: 'SUCCESS', correlationId: 'corr-2', detail: 'Consent directive revoked (CARE_COORDINATION / CLINICAL_CONTEXT)',
    sequenceNo: 1, prevHash: '1'.repeat(64), entryHash: '2'.repeat(64),
  },
  {
    id: 'e1', occurredAt: '2026-09-16T12:00:00Z', actorUserId: 'bbbbbbbb-0000-0000-0000-000000000003',
    action: 'CLAIM_ADJUDICATED', resourceType: 'CLAIM', resourceId: 'cccccccc-0000-0000-0000-000000000004',
    outcome: 'SUCCESS', correlationId: 'corr-1', detail: 'Claim CLM-1 adjudicated v1 (ADJUDICATED)',
    sequenceNo: 0, prevHash: '0'.repeat(64), entryHash: '1'.repeat(64),
  },
]

function renderPage(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AuditEventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listAuditEvents.mockResolvedValue(EVENTS)
  })

  it('lists audit events with their action, detail and fingerprint', async () => {
    renderPage(<AuditEventsPage />)

    expect(await screen.findByText('CLAIM_ADJUDICATED')).toBeInTheDocument()
    expect(screen.getByText('CONSENT_REVOKED')).toBeInTheDocument()
    expect(screen.getByText('Claim CLM-1 adjudicated v1 (ADJUDICATED)')).toBeInTheDocument()
    // The fingerprint is shown truncated (first 8 hex chars of entryHash).
    expect(screen.getByText('11111111…')).toBeInTheDocument()
  })

  it('shows the intact banner when verification passes', async () => {
    const ok: AuditChainVerification = { valid: true, entriesChecked: 2, brokenAtSequence: null, reason: null }
    verifyAuditChain.mockResolvedValue(ok)

    renderPage(<AuditEventsPage />)
    await screen.findByText('CLAIM_ADJUDICATED')
    await userEvent.click(screen.getByRole('button', { name: 'Verify integrity' }))

    expect(await screen.findByText('Chain intact — 2 entries verified.')).toBeInTheDocument()
  })

  it('shows the tampering banner when verification fails', async () => {
    const broken: AuditChainVerification = {
      valid: false, entriesChecked: 1, brokenAtSequence: 1, reason: 'entry-hash mismatch (a field was modified)',
    }
    verifyAuditChain.mockResolvedValue(broken)

    renderPage(<AuditEventsPage />)
    await screen.findByText('CLAIM_ADJUDICATED')
    await userEvent.click(screen.getByRole('button', { name: 'Verify integrity' }))

    expect(
      await screen.findByText(/Tampering detected at sequence 1 — entry-hash mismatch/),
    ).toBeInTheDocument()
  })

  it('filters the rows by action', async () => {
    renderPage(<AuditEventsPage />)
    await screen.findByText('CLAIM_ADJUDICATED')

    // Pick "Consent revoked" from the Action select → the claim row disappears.
    await userEvent.click(screen.getByLabelText('Action'))
    await userEvent.click(await screen.findByRole('option', { name: 'Consent revoked' }))

    await waitFor(() => expect(screen.queryByText('CLAIM_ADJUDICATED')).not.toBeInTheDocument())
    expect(screen.getByText('CONSENT_REVOKED')).toBeInTheDocument()
  })
})
