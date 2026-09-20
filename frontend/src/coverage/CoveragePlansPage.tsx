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
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  TextField,
  Typography,
} from '@mui/material'
import { ApiClientError } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { money } from '../claims/ClaimsPage'
import { useCoveragePlans, useCreateCoveragePlan } from './useCoverage'
import { PageHeading } from '../components/PageHeading'

const ADMIN_ROLES = ['ORG_ADMIN']
const PLAN_TYPES = ['HMO', 'PPO', 'EPO', 'HDHP'] as const

/** A 0..1 coinsurance fraction rendered as a percentage (0.2 → "20%", 0.105 → "10.5%"). */
export function percent(rate: number): string {
  return `${+(rate * 100).toFixed(2)}%`
}

export function CoveragePlansPage() {
  const { data: user } = useCurrentUser()
  const plans = useCoveragePlans()
  const canCreate = (user?.roles ?? []).some((r) => ADMIN_ROLES.includes(r))

  if (plans.isPending) return <LoadingScreen />
  if (plans.isError) return <ErrorScreen error={plans.error} />

  return (
    <Stack spacing={3}>
      <PageHeading>Coverage plans</PageHeading>

      {canCreate && <CreateCoveragePlanForm />}

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Coverage plans">
          <TableHead>
            <TableRow>
              <TableCell>Code</TableCell>
              <TableCell>Name</TableCell>
              <TableCell>Type</TableCell>
              <TableCell align="right">Deductible</TableCell>
              <TableCell align="right">Coinsurance</TableCell>
              <TableCell align="right">Copay</TableCell>
              <TableCell align="right">OOP max</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {plans.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7}>
                  <Typography variant="body2" color="text.secondary">
                    No coverage plans yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              plans.data.map((p) => (
                <TableRow key={p.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/coverage-plans/${p.id}`}>
                      {p.planCode}
                    </Link>
                  </TableCell>
                  <TableCell>{p.name}</TableCell>
                  <TableCell>{p.planType}</TableCell>
                  <TableCell align="right">{money(p.deductibleAmount)}</TableCell>
                  <TableCell align="right">{percent(p.coinsuranceRate)}</TableCell>
                  <TableCell align="right">{money(p.copayAmount)}</TableCell>
                  <TableCell align="right">{p.outOfPocketMax == null ? '—' : money(p.outOfPocketMax)}</TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
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
    <Card variant="outlined">
      <CardContent>
        <Typography variant="subtitle1" gutterBottom>
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
                label="Deductible" type="number" size="small"
                slotProps={{ htmlInput: { step: '0.01', min: '0' } }}
                {...register('deductibleAmount')} error={!!errors.deductibleAmount}
                helperText={errors.deductibleAmount?.message}
              />
              <TextField
                label="Coinsurance (0–1)" type="number" size="small"
                slotProps={{ htmlInput: { step: '0.0001', min: '0', max: '1' } }}
                {...register('coinsuranceRate')} error={!!errors.coinsuranceRate}
                helperText={errors.coinsuranceRate?.message}
              />
              <TextField
                label="Copay" type="number" size="small"
                slotProps={{ htmlInput: { step: '0.01', min: '0' } }}
                {...register('copayAmount')} error={!!errors.copayAmount}
                helperText={errors.copayAmount?.message}
              />
              <TextField
                label="OOP max (optional)" type="number" size="small"
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
