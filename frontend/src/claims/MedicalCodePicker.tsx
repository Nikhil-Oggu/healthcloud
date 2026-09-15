import { useEffect, useMemo, useState } from 'react'
import { Autocomplete, TextField } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { MedicalCode } from '../api/types'

interface Props {
  value: string
  onChange: (code: string) => void
  label?: string
  error?: boolean
  helperText?: string
}

/**
 * A procedure-code picker backed by the global catalog search (GET /api/v1/medical-codes). Searches as the user
 * types (debounced), filtered to PROCEDURE codes (CPT/HCPCS — a claim line bills a procedure). freeSolo, so the
 * field value is always the code string and a user may type a raw code; the backend still validates it.
 */
export function MedicalCodePicker({ value, onChange, label = 'Procedure code', error, helperText }: Props) {
  const [input, setInput] = useState(value ?? '')
  const [debounced, setDebounced] = useState(input)

  useEffect(() => {
    const t = setTimeout(() => setDebounced(input), 250)
    return () => clearTimeout(t)
  }, [input])

  const search = useQuery({
    queryKey: ['medical-codes', 'procedure', debounced],
    queryFn: () => api.searchMedicalCodes({ q: debounced }),
    enabled: debounced.trim().length >= 2,
  })

  const options = useMemo(
    () => (search.data ?? []).filter((c) => c.category === 'Procedure'),
    [search.data],
  )

  return (
    <Autocomplete<MedicalCode | string, false, false, true>
      freeSolo
      options={options}
      // The catalog already filters server-side; don't re-filter locally.
      filterOptions={(x) => x}
      loading={search.isFetching}
      inputValue={input}
      onInputChange={(_, v) => {
        setInput(v)
        onChange(v)
      }}
      onChange={(_, v) => {
        const code = typeof v === 'string' ? v : (v?.code ?? '')
        setInput(code)
        onChange(code)
      }}
      getOptionLabel={(o) => (typeof o === 'string' ? o : o.code)}
      renderOption={(props, o) =>
        typeof o === 'string' ? (
          <li {...props}>{o}</li>
        ) : (
          <li {...props} key={o.id}>
            {o.code} — {o.description} ({o.systemLabel})
          </li>
        )
      }
      renderInput={(params) => (
        <TextField {...params} label={label} size="small" error={error} helperText={helperText} />
      )}
      sx={{ minWidth: 260 }}
    />
  )
}
