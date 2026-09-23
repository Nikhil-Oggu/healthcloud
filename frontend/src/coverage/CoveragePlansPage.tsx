import { useState } from 'react'
import { Link as RouterLink } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import {
  Alert,
  Box,
  Button,
  Card,
  CardContent,
  Chip,
  Divider,
  InputAdornment,
  Link,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material'
import SearchOutlinedIcon from '@mui/icons-material/SearchOutlined'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import ArrowForwardOutlinedIcon from '@mui/icons-material/ArrowForwardOutlined'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { EmptyState } from '../components/EmptyState'
import { money } from '../claims/ClaimsPage'
import { useCoveragePlans, useCreateCoveragePlan } from './useCoverage'
import { PageHeading } from '../components/PageHeading'

const ADMIN_ROLES = ['ORG_ADMIN']
const PLAN_TYPES = ['HMO', 'PPO', 'EPO', 'HDHP'] as const

/** A 0..1 coinsurance fraction rendered as a percentage (0.2 → "20%", 0.105 → "10.5%"). */
export function percent(rate: number): string {
  return `${+(rate * 100).toFixed(2)}%`
}

/** One labelled metric inside a plan card. */
function Metric({ label, value }: { label: string; value: string }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" sx={{ display: 'block' }}>
        {label}
      </Typography>
      <Typography sx={{ fontWeight: 700, fontSize: '1.05rem' }}>{value}</Typography>
    </Box>
  )
}

export function CoveragePlansPage() {
  const { data: user } = useCurrentUser()
  const plans = useCoveragePlans()
  const canCreate = (user?.roles ?? []).some((r) => ADMIN_ROLES.includes(r))

  const [query, setQuery] = useState('')
  const [planType, setPlanType] = useState<(typeof PLAN_TYPES)[number] | ''>('')

  if (plans.isPending) return <LoadingScreen />
  if (plans.isError) return <ErrorScreen error={plans.error} />

  const all = plans.data
  const q = query.trim().toLowerCase()
  const filtered = all.filter((p) => {
    const matchesQ = !q || p.name.toLowerCase().includes(q) || p.planCode.toLowerCase().includes(q)
    const matchesType = !planType || p.planType === planType
    return matchesQ && matchesType
  })

  return (
    <Stack spacing={3}>
      {/* Breadcrumb */}
      <Box>
        <Typography variant="body2" color="text.secondary">
          {user?.organizationName ?? '—'}
          <Box component="span" sx={{ mx: 1, opacity: 0.6 }}>
            /
          </Box>
          Claims &amp; coverage
        </Typography>
        <Divider sx={{ mt: 1.5 }} />
      </Box>

      {/* Title + subtitle */}
      <Box>
        <PageHeading sx={{ mb: 0.5 }}>Coverage plans</PageHeading>
        <Typography color="text.secondary">Review plan details and cost-sharing terms.</Typography>
      </Box>

      {/* Search + type filter */}
      <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 1.5 }}>
        <TextField
          size="small"
          placeholder="Search by plan name or code"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          slotProps={{
            input: {
              startAdornment: (
                <InputAdornment position="start">
                  <SearchOutlinedIcon fontSize="small" />
                </InputAdornment>
              ),
            },
            htmlInput: { 'aria-label': 'Search by plan name or code' },
          }}
          sx={{ minWidth: 260, flexGrow: 1, maxWidth: 460 }}
        />
        <TextField
          select
          label="Plan type"
          size="small"
          value={planType}
          onChange={(e) => setPlanType(e.target.value as (typeof PLAN_TYPES)[number] | '')}
          sx={{ minWidth: 180 }}
        >
          <MenuItem value="">All plan types</MenuItem>
          {PLAN_TYPES.map((t) => (
            <MenuItem key={t} value={t}>
              {t}
            </MenuItem>
          ))}
        </TextField>
      </Stack>

      <Typography variant="body2" color="text.secondary">
        {all.length} {all.length === 1 ? 'plan' : 'plans'}
      </Typography>

      {filtered.length === 0 ? (
        <Card>
          <CardContent>
            <EmptyState message={all.length === 0 ? 'No coverage plans yet.' : 'No plans match your search.'} />
          </CardContent>
        </Card>
      ) : (
        <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' }, gap: 3 }}>
          {filtered.map((p) => (
            <Card key={p.id}>
              <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
                <Stack direction="row" spacing={1} sx={{ alignItems: 'flex-start', justifyContent: 'space-between' }}>
                  <Box
                    sx={{
                      width: 44,
                      height: 44,
                      borderRadius: 2,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      color: 'primary.main',
                      bgcolor: 'rgba(13,148,136,0.10)',
                    }}
                  >
                    <ShieldOutlinedIcon />
                  </Box>
                  <Chip label={p.planType} size="small" />
                </Stack>

                <Box sx={{ mt: 2 }}>
                  <Typography variant="h6" component="h2" sx={{ fontWeight: 700 }}>
                    {p.name}
                  </Typography>
                  <Link component={RouterLink} to={`/coverage-plans/${p.id}`} sx={{ fontWeight: 600 }} underline="hover">
                    {p.planCode}
                  </Link>
                </Box>

                <Divider sx={{ my: 2.5 }} />

                <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2.5 }}>
                  <Metric label="Deductible" value={money(p.deductibleAmount)} />
                  <Metric label="Coinsurance" value={percent(p.coinsuranceRate)} />
                  <Metric label="Copay" value={money(p.copayAmount)} />
                  <Metric label="Out-of-pocket maximum" value={p.outOfPocketMax == null ? '—' : money(p.outOfPocketMax)} />
                </Box>

                <Divider sx={{ my: 2.5 }} />

                <Link
                  component={RouterLink}
                  to={`/coverage-plans/${p.id}`}
                  underline="hover"
                  sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.5, fontWeight: 600 }}
                >
                  View plan
                  <ArrowForwardOutlinedIcon sx={{ fontSize: 16 }} />
                </Link>
              </CardContent>
            </Card>
          ))}
        </Box>
      )}

      {canCreate && <CreateCoveragePlanForm />}
    </Stack>
  )
}

const schema = z.object({
  planCode: z.string().trim().min(1, 'Required').max(32, 'At most 32 characters'),
  name: z.string().trim().min(1, 'Required').max(200, 'At most 200 characters'),
  planType: z.enum(PLAN_TYPES),
  deductibleAmount: z.coerce.number({ message: 'Required' }).min(0, 'Must be ≥ 0'),
  coinsuranceRate: z.coerce.number({ message: 'Required' }).min(0, '0–1').max(1, '0–1'),
  copayAmount: z.coerce.number({ message: 'Required' }).min(0, 'Must be ≥ 0'),
  outOfPocketMax: z.preprocess(
    (v) => (v === '' || v == null ? undefined : v),
    z.coerce.number().min(0, 'Must be ≥ 0').optional(),
  ),
})
type FormInput = z.input<typeof schema>
type FormOutput = z.output<typeof schema>

function CreateCoveragePlanForm() {
  const createPlan = useCreateCoveragePlan()
  const [submitError, setSubmitError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormInput, unknown, FormOutput>({
    resolver: zodResolver(schema),
    defaultValues: {
      planCode: '', name: '', planType: 'PPO',
      deductibleAmount: 0, coinsuranceRate: 0.2, copayAmount: 0, outOfPocketMax: undefined,
    },
  })

  async function onSubmit(values: FormOutput) {
    setSubmitError(null)
    try {
      await createPlan.mutateAsync({
        planCode: values.planCode,
        name: values.name,
        planType: values.planType,
        deductibleAmount: values.deductibleAmount,
        coinsuranceRate: values.coinsuranceRate,
        copayAmount: values.copayAmount,
        outOfPocketMax: values.outOfPocketMax,
      })
      reset()
    } catch (err) {
      setSubmitError(err instanceof ApiClientError ? err.message : 'Could not create the plan.')
    }
  }

  return (
    <Card>
      <CardContent sx={{ p: { xs: 2.5, sm: 3 } }}>
        <Typography variant="h6" component="h2" sx={{ fontWeight: 700, mb: 2 }}>
          New coverage plan
        </Typography>
        {submitError && (
          <Alert severity="error" sx={{ mb: 2 }} onClose={() => setSubmitError(null)}>
            {submitError}
          </Alert>
        )}
        <Box component="form" onSubmit={handleSubmit(onSubmit)} noValidate>
          <Stack spacing={2}>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Plan code" size="small" fullWidth
                {...register('planCode')} error={!!errors.planCode} helperText={errors.planCode?.message}
              />
              <TextField
                label="Name" size="small" fullWidth
                {...register('name')} error={!!errors.name} helperText={errors.name?.message}
              />
              <TextField
                select label="Type" size="small" sx={{ minWidth: 120 }}
                slotProps={{ select: { native: true }, inputLabel: { shrink: true } }} {...register('planType')}
              >
                {PLAN_TYPES.map((t) => (
                  <option key={t} value={t}>{t}</option>
                ))}
              </TextField>
            </Stack>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
              <TextField
                label="Deductible" type="number" size="small" sx={{ minWidth: 150 }}
                slotProps={{ htmlInput: { step: '0.01', min: '0' } }}
                {...register('deductibleAmount')} error={!!errors.deductibleAmount}
                helperText={errors.deductibleAmount?.message}
              />
              <TextField
                label="Coinsurance (0–1)" type="number" size="small" sx={{ minWidth: 150 }}
                slotProps={{ htmlInput: { step: '0.0001', min: '0', max: '1' } }}
                {...register('coinsuranceRate')} error={!!errors.coinsuranceRate}
                helperText={errors.coinsuranceRate?.message}
              />
              <TextField
                label="Copay" type="number" size="small" sx={{ minWidth: 150 }}
                slotProps={{ htmlInput: { step: '0.01', min: '0' } }}
                {...register('copayAmount')} error={!!errors.copayAmount}
                helperText={errors.copayAmount?.message}
              />
              <TextField
                label="OOP max (optional)" type="number" size="small" sx={{ minWidth: 150 }}
                slotProps={{ htmlInput: { step: '0.01', min: '0' } }}
                {...register('outOfPocketMax')} error={!!errors.outOfPocketMax}
                helperText={errors.outOfPocketMax?.message}
              />
            </Stack>
            <Box>
              <Button type="submit" variant="contained" disabled={createPlan.isPending}>
                {createPlan.isPending ? 'Creating…' : 'Create plan'}
              </Button>
            </Box>
          </Stack>
        </Box>
      </CardContent>
    </Card>
  )
}
