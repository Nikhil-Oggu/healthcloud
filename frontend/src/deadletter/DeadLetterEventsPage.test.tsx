import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { DeadLetterEventsPage } from './DeadLetterEventsPage'
import type { DeadLetterEvent } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      listDeadLetterEvents: vi.fn(),
      replayDeadLetterEvent: vi.fn(),
    },
  }
})

const listDeadLetterEvents = vi.mocked(api.listDeadLetterEvents)
const replayDeadLetterEvent = vi.mocked(api.replayDeadLetterEvent)

const PENDING: DeadLetterEvent = {
  id: 'dl-1',
  organizationId: 'org-1',
  sourceTopic: 'claim.adjudicated',
  messageKey: 'claim-1',
  payload: '{"claimId":"c1","claimNumber":"CLM-1"}',
  eventId: 'aaaaaaaa-0000-0000-0000-000000000001',
  exceptionType: 'tools.jackson.core.JacksonException',
  exceptionMessage: 'Unrecognized token',
  createdAt: '2026-09-16T12:00:00Z',
  replayedAt: null,
  replayedBy: null,
}

const REPLAYED: DeadLetterEvent = {
  ...PENDING,
  id: 'dl-2',
  eventId: 'bbbbbbbb-0000-0000-0000-000000000002',
  replayedAt: '2026-09-16T13:00:00Z',
  replayedBy: 'admin-1',
}

function renderPage(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('DeadLetterEventsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listDeadLetterEvents.mockResolvedValue([PENDING, REPLAYED])
  })

  it('lists dead letters and marks an already-replayed one', async () => {
    renderPage(<DeadLetterEventsPage />)

    // Both rows show their source topic; the already-replayed row shows the Replayed chip.
    expect(await screen.findAllByText('claim.adjudicated')).toHaveLength(2)
    expect(screen.getByText('Replayed')).toBeInTheDocument()
    // Exactly one row is replayable — the pending one.
    expect(screen.getAllByRole('button', { name: 'Replay' })).toHaveLength(1)
  })

  it('replays a pending record after confirmation', async () => {
    replayDeadLetterEvent.mockResolvedValue({ ...PENDING, replayedAt: '2026-09-16T14:00:00Z', replayedBy: 'admin-1' })
    renderPage(<DeadLetterEventsPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(replayDeadLetterEvent).toHaveBeenCalledWith('dl-1'))
  })
})
