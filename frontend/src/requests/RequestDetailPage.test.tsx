import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { RequestDetailPage } from './RequestDetailPage'
import type { CurrentUser, RequestComment, RequestStatusHistory, ServiceRequest } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      ...actual.api,
      getRequest: vi.fn(),
      getRequestHistory: vi.fn(),
      changeRequestStatus: vi.fn(),
      listComments: vi.fn(),
      addComment: vi.fn(),
    },
  }
})
vi.mock('../auth/useAuth', () => ({ useCurrentUser: vi.fn() }))

const getRequest = vi.mocked(api.getRequest)
const getRequestHistory = vi.mocked(api.getRequestHistory)
const changeRequestStatus = vi.mocked(api.changeRequestStatus)
const listComments = vi.mocked(api.listComments)
const addComment = vi.mocked(api.addComment)
const useCurrentUserMock = vi.mocked(useCurrentUser)

const DRAFT: ServiceRequest = {
  id: 'r1', patientId: 'p1', type: 'CLAIM_SUPPORT', status: 'DRAFT', priority: 'NORMAL',
  title: 'Help with a claim', description: 'Please help', createdBy: 'u1', version: 3, createdAt: '2026-09-13T10:00:00Z',
}
const HISTORY: RequestStatusHistory[] = [
  { id: 'h1', fromStatus: null, toStatus: 'DRAFT', actorUserId: 'u1', reason: 'Request created', createdAt: '2026-09-13T10:00:00Z' },
]
const COMMENTS: RequestComment[] = [
  { id: 'c1', authorUserId: 'u1', body: 'First note', createdAt: '2026-09-13T10:05:00Z' },
]

function mockUser(roles: string[]) {
  useCurrentUserMock.mockReturnValue({
    data: { userId: 'u1', email: 'x@northcare.example.org', fullName: 'X',
      organizationId: 'o1', organizationName: 'NorthCare Health', roles } as CurrentUser,
  } as ReturnType<typeof useCurrentUser>)
}

function renderDetail(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/requests/r1']}>
        <Routes>
          <Route path="/requests/:id" element={ui} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RequestDetailPage', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the status and the timeline', async () => {
    mockUser(['CARE_COORDINATOR'])
    getRequest.mockResolvedValue(DRAFT)
    getRequestHistory.mockResolvedValue(HISTORY)
    listComments.mockResolvedValue([])

    renderDetail(<RequestDetailPage />)

    expect(await screen.findByText('Help with a claim')).toBeInTheDocument()
    expect(screen.getByText('Created as DRAFT')).toBeInTheDocument()
  })

  it('submitting a draft sends the transition with the loaded version', async () => {
    mockUser(['CARE_COORDINATOR'])
    getRequest.mockResolvedValue(DRAFT)
    getRequestHistory.mockResolvedValue(HISTORY)
    listComments.mockResolvedValue([])
    changeRequestStatus.mockResolvedValue({ ...DRAFT, status: 'SUBMITTED', version: 4 })

    renderDetail(<RequestDetailPage />)
    await screen.findByText('Help with a claim')

    await userEvent.click(screen.getByRole('button', { name: 'Submit' }))

    await waitFor(() =>
      expect(changeRequestStatus).toHaveBeenCalledWith('r1', {
        targetStatus: 'SUBMITTED',
        expectedVersion: 3,
        reason: undefined,
      }),
    )
  })

  it('a patient sees no coordinator-only actions on a DRAFT', async () => {
    mockUser(['PATIENT'])
    getRequest.mockResolvedValue(DRAFT)
    getRequestHistory.mockResolvedValue(HISTORY)
    listComments.mockResolvedValue([])

    renderDetail(<RequestDetailPage />)
    await screen.findByText('Help with a claim')

    // A patient may Submit or Cancel a DRAFT, but never Triage it.
    expect(screen.getByRole('button', { name: 'Submit' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Triage' })).not.toBeInTheDocument()
  })

  it('renders the comment thread', async () => {
    mockUser(['CARE_COORDINATOR'])
    getRequest.mockResolvedValue(DRAFT)
    getRequestHistory.mockResolvedValue(HISTORY)
    listComments.mockResolvedValue(COMMENTS)

    renderDetail(<RequestDetailPage />)

    expect(await screen.findByText('First note')).toBeInTheDocument()
  })

  it('a participant can post a comment', async () => {
    mockUser(['CARE_COORDINATOR'])
    getRequest.mockResolvedValue(DRAFT)
    getRequestHistory.mockResolvedValue(HISTORY)
    listComments.mockResolvedValue([])
    addComment.mockResolvedValue({ id: 'c2', authorUserId: 'u1', body: 'A new note', createdAt: '2026-09-13T11:00:00Z' })

    renderDetail(<RequestDetailPage />)
    await screen.findByText('Help with a claim')

    await userEvent.type(screen.getByLabelText('Add a comment'), 'A new note')
    await userEvent.click(screen.getByRole('button', { name: 'Comment' }))

    await waitFor(() => expect(addComment).toHaveBeenCalledWith('r1', { body: 'A new note' }))
  })

  it('a read-only role sees no comment box', async () => {
    mockUser(['CLAIMS_REVIEWER'])
    getRequest.mockResolvedValue(DRAFT)
    getRequestHistory.mockResolvedValue(HISTORY)
    listComments.mockResolvedValue(COMMENTS)

    renderDetail(<RequestDetailPage />)
    await screen.findByText('First note')

    expect(screen.queryByLabelText('Add a comment')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()
  })
})
