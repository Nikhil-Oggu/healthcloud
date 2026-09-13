import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiClientError } from '../api/client'
import { HomePage } from '../pages/HomePage'
import { ProtectedRoute } from '../auth/ProtectedRoute'
import { ErrorScreen } from '../components/ErrorScreen'

// Mock only the `api` surface; keep the real ApiClientError class for instanceof checks.
vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: { me: vi.fn(), devLogin: vi.fn(), logout: vi.fn() },
  }
})

const meMock = vi.mocked(api.me)

function renderWithProviders(ui: ReactNode, initialEntries: string[] = ['/']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('authenticated shell', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders the org and roles from /me', async () => {
    meMock.mockResolvedValue({
      userId: 'u1',
      email: 'provider@northcare.example.org',
      fullName: 'Dana Provider',
      organizationId: 'o1',
      organizationName: 'NorthCare Health',
      roles: ['PROVIDER'],
    })

    renderWithProviders(<HomePage />)

    expect(await screen.findByText('NorthCare Health')).toBeInTheDocument()
    expect(screen.getByText('PROVIDER')).toBeInTheDocument()
    expect(screen.getByText('Dana Provider')).toBeInTheDocument()
  })

  it('redirects to /login when /me returns 401', async () => {
    meMock.mockRejectedValue(
      new ApiClientError(401, { code: 'UNAUTHENTICATED', message: 'Authentication is required.' }),
    )

    renderWithProviders(
      <Routes>
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <div>secret content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>login page</div>} />
      </Routes>,
    )

    expect(await screen.findByText('login page')).toBeInTheDocument()
    expect(screen.queryByText('secret content')).not.toBeInTheDocument()
  })
})

describe('ErrorScreen', () => {
  it('shows the backend message and correlation id', () => {
    const error = new ApiClientError(500, {
      code: 'INTERNAL_ERROR',
      message: 'An unexpected error occurred.',
      correlationId: 'abc-123',
    })

    render(<ErrorScreen error={error} />)

    expect(screen.getByText('INTERNAL_ERROR')).toBeInTheDocument()
    expect(screen.getByText('An unexpected error occurred.')).toBeInTheDocument()
    expect(screen.getByText(/abc-123/)).toBeInTheDocument()
  })
})
