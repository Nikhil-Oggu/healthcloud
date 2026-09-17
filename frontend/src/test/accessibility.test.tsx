import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { expectNoAxeViolations } from './axe'
import { AppLayout } from '../layout/AppLayout'
import { PageHeading } from '../components/PageHeading'
import { NotFoundPage } from '../pages/NotFoundPage'
import { DeniedPage } from '../pages/DeniedPage'
import { useCurrentUser } from '../auth/useAuth'

// AppLayout reads the current user; mock the hook (and the api it would call on logout).
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, logout: vi.fn() } }
})

const useCurrentUserMock = vi.mocked(useCurrentUser)

function mockUser(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: {
      userId: 'u1',
      email: 'x@northcare.example.org',
      fullName: 'X',
      organizationId: 'o1',
      organizationName: 'NorthCare Health',
      roles,
    },
  } as ReturnType<typeof useCurrentUser>)
}

function renderRoute(ui: ReactNode, path = '/') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[path]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('accessibility — app shell', () => {
  beforeEach(() => vi.clearAllMocks())

  it('has a skip link, a named primary nav, and a single page-level h1', async () => {
    mockUser(['ORG_ADMIN'])
    const { container } = renderRoute(
      <Routes>
        <Route element={<AppLayout />}>
          <Route index element={<PageHeading>Dashboard</PageHeading>} />
        </Route>
      </Routes>,
    )

    // WCAG 2.4.1 bypass blocks: a skip link that targets the main landmark.
    const skip = screen.getByRole('link', { name: /skip to main content/i })
    expect(skip).toHaveAttribute('href', '#main')
    // The primary navigation is a named landmark.
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument()
    // Exactly one first-level heading on the page (from PageHeading), and the brand is not a heading.
    const h1s = screen.getAllByRole('heading', { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent('Dashboard')

    await expectNoAxeViolations(container)
  })
})

describe('accessibility — standalone pages', () => {
  it('NotFoundPage has no axe violations', async () => {
    const { container } = renderRoute(<NotFoundPage />)
    expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument()
    await expectNoAxeViolations(container)
  })

  it('DeniedPage has no axe violations', async () => {
    const { container } = renderRoute(<DeniedPage />)
    expect(screen.getByRole('heading', { level: 1, name: 'Access denied' })).toBeInTheDocument()
    await expectNoAxeViolations(container)
  })
})
