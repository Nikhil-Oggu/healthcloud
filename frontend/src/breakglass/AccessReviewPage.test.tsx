import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { AccessReviewPage } from './AccessReviewPage'
import type { BreakGlassGrantAdmin, CurrentUser } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { ...actual.api, listAllBreakGlass: vi.fn(), revokeBreakGlass: vi.fn() },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const listAllBreakGlass = vi.mocked(api.listAllBreakGlass)
const revokeBreakGlass = vi.mocked(api.revokeBreakGlass)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const GRANTS: BreakGlassGrantAdmin[] = [
  {
    id: 'g1', providerUserId: 'prov-morgan', providerName: 'Morgan Provider', patientId: 'pat-1',
    reason: 'ER: unconscious', createdAt: '2026-09-16T12:00:00Z', expiresAt: '2026-09-16T13:00:00Z',
  },
]

function mockUser(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: { userId: 'u1', email: 'x@northcare.example.org', fullName: 'X',
      organizationId: 'o1', organizationName: 'NorthCare Health', roles } as CurrentUser,
  } as ReturnType<typeof useCurrentUser>)
}

function renderPage(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('AccessReviewPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists active grants with the provider name and a patient link', async () => {
    mockUser(['ORG_ADMIN'])
    listAllBreakGlass.mockResolvedValue(GRANTS)

    renderPage(<AccessReviewPage />)

    expect(await screen.findByText('Morgan Provider')).toBeInTheDocument()
    expect(screen.getByText('ER: unconscious')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'pat-1' })).toHaveAttribute('href', '/patients/pat-1')
  })

  it('lets an admin revoke a grant (with an inline confirm)', async () => {
    mockUser(['ORG_ADMIN'])
    listAllBreakGlass.mockResolvedValue(GRANTS)
    revokeBreakGlass.mockResolvedValue({ ...GRANTS[0] })

    renderPage(<AccessReviewPage />)
    await screen.findByText('Morgan Provider')

    await userEvent.click(screen.getByRole('button', { name: 'Revoke' }))
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(revokeBreakGlass).toHaveBeenCalledWith('g1'))
  })

  it('does not show a Revoke button to a read-only auditor', async () => {
    mockUser(['AUDITOR'])
    listAllBreakGlass.mockResolvedValue(GRANTS)

    renderPage(<AccessReviewPage />)

    expect(await screen.findByText('Morgan Provider')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke' })).not.toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    mockUser(['ORG_ADMIN'])
    listAllBreakGlass.mockResolvedValue([])

    renderPage(<AccessReviewPage />)

    expect(await screen.findByText('No active emergency access.')).toBeInTheDocument()
  })
})
