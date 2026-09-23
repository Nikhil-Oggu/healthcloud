import { useEffect, useMemo, useState } from 'react'
import { Autocomplete, Box, TextField, Typography } from '@mui/material'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { MedicalCode } from '../api/types'

interface Props {
  value: string
  onChange: (code: string) => void
  label?: string
  error?: boolean
  helperText?: string
  /** Which catalog category to show. Defaults to Procedure (claim lines / prior auth); Diagnosis for referrals. */
  category?: 'Procedure' | 'Diagnosis'
  /**
   * Render the label ABOVE the field (the design's label-on-top style) instead of MUI's floating label.
   * The accessible name is preserved via `aria-label`, so `getByLabelText(label)` still resolves. Default off,
   * so the create forms (claims/prior-auth/referrals) keep their inline label unchanged.
   */
  labelAbove?: boolean
}

/**
 * A medical-code picker backed by the global catalog search (GET /api/v1/medical-codes). Searches as the user
 * types (debounced), filtered to one catalog category — PROCEDURE codes (CPT/HCPCS) by default, or DIAGNOSIS
 * codes (ICD-10-CM) when `category="Diagnosis"` (e.g. a referral's coded reason). freeSolo, so the field value
 * is always the code string and a user may type a raw code; the backend still validates it.
 */
export function MedicalCodePicker({
  value,
  onChange,
  label = 'Procedure code',
  error,
  helperText,
  category = 'Procedure',
  labelAbove = false,
}: Props) {
  const [input, setInput] = useState(value ?? '')
  const [debounced, setDebounced] = useState(input)

  useEffect(() => {
    const t = setTimeout(() => setDebounced(input), 250)
    return () => clearTimeout(t)
  }, [input])

  const search = useQuery({
    queryKey: ['medical-codes', category, debounced],
    queryFn: () => api.searchMedicalCodes({ q: debounced }),
    enabled: debounced.trim().length >= 2,
  })

  const options = useMemo(
    () => (search.data ?? []).filter((c) => c.category === category),
    [search.data, category],
  )

  const control = (
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
        <TextField
          {...params}
          label={labelAbove ? undefined : label}
          size="small"
          error={error}
          helperText={helperText}
          // In label-above mode there's no floating label, so give the input its accessible name via aria-label
          // (merged into the slotProps the Autocomplete supplies) — keeps getByLabelText(label) working.
          slotProps={
            labelAbove
              ? { ...params.slotProps, htmlInput: { ...params.slotProps?.htmlInput, 'aria-label': label } }
              : params.slotProps
          }
        />
      )}
      sx={{ minWidth: labelAbove ? 0 : 260 }}
    />
  )

  if (!labelAbove) return control

  return (
    <Box sx={{ flex: 1, minWidth: 0 }}>
      <Typography variant="body2" sx={{ display: 'block', mb: 0.75, fontWeight: 500, color: 'text.secondary' }}>
        {label}
      </Typography>
      {control}
    </Box>
  )
}
