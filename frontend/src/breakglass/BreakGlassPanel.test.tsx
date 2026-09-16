import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { BreakGlassPanel } from './BreakGlassPanel'
import type { BreakGlassGrant } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, breakGlass: vi.fn() } }
})

const breakGlass = vi.mocked(api.breakGlass)

const GRANT: BreakGlassGrant = {
  id: 'g1', patientId: 'pat-1', reason: 'ER: unconscious', createdAt: '2026-09-16T12:00:00Z',
  expiresAt: '2026-09-16T13:00:00Z', active: true,
}

function renderPanel(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('BreakGlassPanel', () => {
  beforeEach(() => vi.clearAllMocks())

  it('breaks the glass with the patient id and the entered reason', async () => {
    breakGlass.mockResolvedValue(GRANT)
    renderPanel(<BreakGlassPanel patientId="pat-1" />)

    await userEvent.type(screen.getByLabelText(/Reason for emergency access/), 'ER: unconscious')
    await userEvent.click(screen.getByRole('button', { name: 'Break glass' }))

    await waitFor(() =>
      expect(breakGlass).toHaveBeenCalledWith({ patientId: 'pat-1', reason: 'ER: unconscious' }),
    )
  })

  it('requires a reason (does not call the API when blank)', async () => {
    renderPanel(<BreakGlassPanel patientId="pat-1" />)

    await userEvent.click(screen.getByRole('button', { name: 'Break glass' }))

    expect(await screen.findByText('A reason is required')).toBeInTheDocument()
    expect(breakGlass).not.toHaveBeenCalled()
  })
})
