import { Link as RouterLink } from 'react-router-dom'
import {
  Chip,
  Link,
  Paper,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material'
import { usePatients } from '../patients/usePatients'
import { useClaims } from '../claims/useClaims'
import { useCurrentUser } from '../auth/useAuth'
import { LoadingScreen } from '../components/LoadingScreen'
import { ErrorScreen } from '../components/ErrorScreen'
import { claimReviewStatusColor } from './statusColor'
import { CreateClaimReviewForm } from './CreateClaimReviewForm'
import { useClaimReviews } from './useClaimReview'

// Roles allowed to open a review (mirrors the backend gate; the server still enforces it).
const OPEN_ROLES = ['CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN']

export function ClaimReviewsPage() {
  const { data: user } = useCurrentUser()
  const reviews = useClaimReviews()
  const patients = usePatients()
  const claims = useClaims()
  const canOpen = (user?.roles ?? []).some((r) => OPEN_ROLES.includes(r))

  if (reviews.isPending) return <LoadingScreen />
  if (reviews.isError) return <ErrorScreen error={reviews.error} />

  const patientName = (patientId: string) =>
    (patients.data ?? []).find((p) => p.id === patientId)?.fullName ?? '—'
  const claimNumber = (claimId: string) =>
    (claims.data ?? []).find((c) => c.id === claimId)?.claimNumber ?? '—'

  return (
    <Stack spacing={3}>
      <Typography variant="h5">Manual review</Typography>

      {canOpen && <CreateClaimReviewForm />}

      <TableContainer component={Paper} variant="outlined">
        <Table aria-label="Claim reviews">
          <TableHead>
            <TableRow>
              <TableCell>Review #</TableCell>
              <TableCell>Patient</TableCell>
              <TableCell>Claim</TableCell>
              <TableCell>Status</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {reviews.data.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4}>
                  <Typography variant="body2" color="text.secondary">
                    No reviews yet.
                  </Typography>
                </TableCell>
              </TableRow>
            ) : (
              reviews.data.map((r) => (
                <TableRow key={r.id} hover>
                  <TableCell>
                    <Link component={RouterLink} to={`/claim-reviews/${r.id}`}>
                      {r.reviewNumber}
                    </Link>
                  </TableCell>
                  <TableCell>{patientName(r.patientId)}</TableCell>
                  <TableCell>{claimNumber(r.claimId)}</TableCell>
                  <TableCell>
                    <Chip label={r.status} size="small" color={claimReviewStatusColor(r.status)} />
                  </TableCell>
                </TableRow>
              ))
            )}
          </TableBody>
        </Table>
      </TableContainer>
    </Stack>
  )
}
