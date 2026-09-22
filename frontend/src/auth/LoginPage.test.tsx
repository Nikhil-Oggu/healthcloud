import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { LoginPage } from './LoginPage'
import { expectNoAxeViolations } from '../test/axe'

// Mock only the `api` surface; /me returns "not logged in" so the login page renders.
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { me: vi.fn(), devLogin: vi.fn(), logout: vi.fn(), authConfig: vi.fn() },
  }
})

const meMock = vi.mocked(api.me)

function renderLogin(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/login']}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('LoginPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Not authenticated → the login page shows its sign-in options.
    meMock.mockRejectedValue(new Error('not logged in'))
    // Default: Cognito available (the button renders as a live link). Tests can override per case.
    vi.mocked(api.authConfig).mockResolvedValue({ cognitoEnabled: true })
  })

  it('offers "Sign in with Cognito" as a full-page link to the BFF OIDC endpoint', () => {
    renderLogin(<LoginPage />)
    const cognito = screen.getByRole('link', { name: /sign in with cognito/i })
    expect(cognito).toHaveAttribute('href', '/oauth2/authorization/cognito')
  })

  it('renders the landing hero and the clickable role personas', () => {
    renderLogin(<LoginPage />)
    // The hero headline is the single <h1>.
    expect(screen.getByRole('heading', { level: 1, name: /care coordinated/i })).toBeInTheDocument()
    // The role personas are clickable buttons. They render in two placements (an inline desktop row
    // and a wrapped mobile/tablet row, toggled by CSS), so both copies are in the DOM.
    expect(screen.getAllByRole('button', { name: /care coordinator/i }).length).toBeGreaterThanOrEqual(1)
    expect(screen.getAllByRole('button', { name: /auditor/i }).length).toBeGreaterThanOrEqual(1)
  })

  it('has no automated accessibility violations', async () => {
    const { container } = renderLogin(<LoginPage />)
    await expectNoAxeViolations(container)
  })
})
