import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { HomePage } from './HomePage'
import { useCurrentUser } from '../auth/useAuth'
import { api } from '../api/client'
import { expectNoAxeViolations } from '../test/axe'

vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      listPatients: vi.fn(),
      listClaimsPage: vi.fn(),
      listPriorAuthorizations: vi.fn(),
      listReferrals: vi.fn(),
      listAppeals: vi.fn(),
      listClaimReviews: vi.fn(),
    },
  }
})

const useCurrentUserMock = vi.mocked(useCurrentUser)

function mockUser(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: {
      userId: 'u1',
      email: 'admin@northcare.example.org',
      fullName: 'Dana Provider',
      organizationId: 'o1',
      organizationName: 'NorthCare Health',
      roles,
    },
  } as ReturnType<typeof useCurrentUser>)
}

function renderHome() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('HomePage dashboard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listPatients).mockResolvedValue([])
    const emptyPage = { content: [], page: 0, size: 1, totalElements: 0, totalPages: 0, first: true, last: true }
    vi.mocked(api.listClaimsPage).mockResolvedValue(emptyPage as never)
    vi.mocked(api.listPriorAuthorizations).mockResolvedValue(emptyPage as never)
    vi.mocked(api.listReferrals).mockResolvedValue(emptyPage as never)
    vi.mocked(api.listAppeals).mockResolvedValue(emptyPage as never)
    vi.mocked(api.listClaimReviews).mockResolvedValue(emptyPage as never)
  })

  it('greets the user and shows role-aware quick-access tiles', () => {
    mockUser(['ORG_ADMIN'])
    renderHome()

    expect(screen.getByRole('heading', { level: 1, name: /welcome back, dana provider/i })).toBeInTheDocument()
    // A launchpad tile links to a work area (ORG_ADMIN sees Claims).
    const claims = screen.getAllByRole('link', { name: /claims/i })[0]
    expect(claims).toHaveAttribute('href', '/claims')
    // A governance area only ORG_ADMIN/AUDITOR can reach.
    expect(screen.getByRole('link', { name: /tamper-evident event log/i })).toBeInTheDocument()
  })

  it('shows a real count once the query resolves', async () => {
    vi.mocked(api.listClaimsPage).mockResolvedValue({
      content: [], page: 0, size: 1, totalElements: 42, totalPages: 42, first: true, last: false,
    } as never)
    mockUser(['ORG_ADMIN'])
    renderHome()

    expect(await screen.findByText('42')).toBeInTheDocument()
  })

  it('has no automated accessibility violations', async () => {
    mockUser(['ORG_ADMIN'])
    const { container } = renderHome()
    await expectNoAxeViolations(container)
  })
})
