import type { ReactNode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '../api/client'
import { MedicalCodePicker } from './MedicalCodePicker'
import type { MedicalCode } from '../api/types'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, api: { ...actual.api, searchMedicalCodes: vi.fn() } }
})

const searchMedicalCodes = vi.mocked(api.searchMedicalCodes)

const PROCEDURE: MedicalCode = {
  id: 'm1', codeSystem: 'CPT', systemLabel: 'CPT', category: 'Procedure', code: '99213',
  description: 'Office/outpatient visit',
}
// A diagnosis must be filtered out — a claim line bills a procedure.
const DIAGNOSIS: MedicalCode = {
  id: 'm2', codeSystem: 'ICD10CM', systemLabel: 'ICD-10-CM', category: 'Diagnosis', code: '99999',
  description: 'Should not appear',
}

function renderPicker(ui: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>)
}

describe('MedicalCodePicker', () => {
  beforeEach(() => vi.clearAllMocks())

  it('searches the catalog and shows procedure options', async () => {
    searchMedicalCodes.mockResolvedValue([PROCEDURE, DIAGNOSIS])

    renderPicker(<MedicalCodePicker value="" onChange={() => {}} />)

    await userEvent.type(screen.getByLabelText('Procedure code'), '992')

    // The search runs after the debounce…
    await waitFor(() => expect(searchMedicalCodes).toHaveBeenCalled())
    // …and the procedure option renders (the diagnosis is filtered out).
    expect(await screen.findByText(/99213 — Office\/outpatient visit \(CPT\)/)).toBeInTheDocument()
    expect(screen.queryByText(/Should not appear/)).not.toBeInTheDocument()
  })

  it('reports the typed code to onChange (freeSolo)', async () => {
    searchMedicalCodes.mockResolvedValue([])
    const onChange = vi.fn()

    renderPicker(<MedicalCodePicker value="" onChange={onChange} />)
    await userEvent.type(screen.getByLabelText('Procedure code'), '80053')

    expect(onChange).toHaveBeenLastCalledWith('80053')
  })
})
