import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { MyBreakGlassPage } from './MyBreakGlassPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, listBreakGlass: vi.fn() } }
})

const listBreakGlass = vi.mocked(api.listBreakGlass)

function renderPage(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('MyBreakGlassPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists the caller grants with a link to the patient', async () => {
    listBreakGlass.mockResolvedValue([
      { id: 'g1', patientId: 'pat-1', reason: 'ER: unconscious', createdAt: '2026-09-16T12:00:00Z',
        expiresAt: '2026-09-16T13:00:00Z', active: true },
    ])

    renderPage(<MyBreakGlassPage />)

    expect(await screen.findByText('ER: unconscious')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'pat-1' })).toHaveAttribute('href', '/patients/pat-1')
  })

  it('shows an empty state', async () => {
    listBreakGlass.mockResolvedValue([])
    renderPage(<MyBreakGlassPage />)
    expect(await screen.findByText('No active emergency access.')).toBeInTheDocument()
  })
})
