import {
  Box,
  Card,
  CardActionArea,
  Chip,
  Stack,
  Typography,
} from '@mui/material'
import { useQueries } from '@tanstack/react-query'
import { Link as RouterLink } from 'react-router-dom'
import type { SvgIconComponent } from '@mui/icons-material'
import PeopleOutlinedIcon from '@mui/icons-material/PeopleOutlined'
import AssignmentOutlinedIcon from '@mui/icons-material/AssignmentOutlined'
import CallSplitOutlinedIcon from '@mui/icons-material/CallSplitOutlined'
import MedicalServicesOutlinedIcon from '@mui/icons-material/MedicalServicesOutlined'
import ReceiptLongOutlinedIcon from '@mui/icons-material/ReceiptLongOutlined'
import ShieldOutlinedIcon from '@mui/icons-material/ShieldOutlined'
import FactCheckOutlinedIcon from '@mui/icons-material/FactCheckOutlined'
import GavelOutlinedIcon from '@mui/icons-material/GavelOutlined'
import RateReviewOutlinedIcon from '@mui/icons-material/RateReviewOutlined'
import AutorenewOutlinedIcon from '@mui/icons-material/AutorenewOutlined'
import HistoryEduOutlinedIcon from '@mui/icons-material/HistoryEduOutlined'
import ManageAccountsOutlinedIcon from '@mui/icons-material/ManageAccountsOutlined'
import ReportProblemOutlinedIcon from '@mui/icons-material/ReportProblemOutlined'
import { useCurrentUser } from '../auth/useAuth'
import { api } from '../api/client'
import { ConstellationBackground } from '../components/ConstellationBackground'
import { constellation, MONO } from '../theme'

function hasAnyRole(roles: string[], allowed: string[]): boolean {
  return roles.some((r) => allowed.includes(r))
}

// Real, role-gated "at a glance" counts pulled from the existing paged endpoints' totalElements
// (a cheap size=1 query) — never fabricated numbers (project rule 2). Each links to its queue.
type StatDef = {
  key: string
  label: string
  to: string
  icon: SvgIconComponent
  roles: string[]
  count: () => Promise<number>
}

const STAT_DEFS: StatDef[] = [
  { key: 'patients', label: 'Patients', to: '/patients', icon: PeopleOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'], count: () => api.listPatients().then((a) => a.length) },
  { key: 'claims', label: 'Claims', to: '/claims', icon: ReceiptLongOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'], count: () => api.listClaimsPage({ size: 1 }).then((p) => p.totalElements) },
  { key: 'priorauth', label: 'Prior auth', to: '/prior-authorizations', icon: FactCheckOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'], count: () => api.listPriorAuthorizations({ size: 1 }).then((p) => p.totalElements) },
  { key: 'referrals', label: 'Referrals', to: '/referrals', icon: CallSplitOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'], count: () => api.listReferrals({ size: 1 }).then((p) => p.totalElements) },
  { key: 'appeals', label: 'Appeals', to: '/appeals', icon: GavelOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'], count: () => api.listAppeals({ size: 1 }).then((p) => p.totalElements) },
  { key: 'reviews', label: 'Reviews', to: '/claim-reviews', icon: RateReviewOutlinedIcon, roles: ['CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'], count: () => api.listClaimReviews({ size: 1 }).then((p) => p.totalElements) },
]

type TileDef = { label: string; to: string; icon: SvgIconComponent; desc: string; roles: string[] }

const TILES: TileDef[] = [
  { label: 'Patients', to: '/patients', icon: PeopleOutlinedIcon, desc: 'People you coordinate care for', roles: ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'] },
  { label: 'Requests', to: '/requests', icon: AssignmentOutlinedIcon, desc: 'Service requests & triage', roles: ['PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'] },
  { label: 'Claims', to: '/claims', icon: ReceiptLongOutlinedIcon, desc: 'Submit, review & adjudicate', roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
  { label: 'Coverage', to: '/coverage-plans', icon: ShieldOutlinedIcon, desc: 'Plans, benefits & networks', roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
  { label: 'Prior auth', to: '/prior-authorizations', icon: FactCheckOutlinedIcon, desc: 'Pre-approve planned services', roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
  { label: 'Referrals', to: '/referrals', icon: CallSplitOutlinedIcon, desc: 'Specialty referrals', roles: ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'] },
  { label: 'Appeals', to: '/appeals', icon: GavelOutlinedIcon, desc: 'Dispute claim decisions', roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
  { label: 'Reviews', to: '/claim-reviews', icon: RateReviewOutlinedIcon, desc: 'Manual review cases', roles: ['CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
  { label: 'Reprocessing', to: '/reprocessing', icon: AutorenewOutlinedIcon, desc: 'Batch re-adjudication', roles: ['CLAIMS_REVIEWER', 'ORG_ADMIN'] },
  { label: 'Audit', to: '/audit', icon: HistoryEduOutlinedIcon, desc: 'Tamper-evident event log', roles: ['AUDITOR', 'ORG_ADMIN'] },
  { label: 'Access review', to: '/access-review', icon: ManageAccountsOutlinedIcon, desc: 'Review break-glass grants', roles: ['AUDITOR', 'ORG_ADMIN'] },
  { label: 'Dead letters', to: '/dead-letters', icon: ReportProblemOutlinedIcon, desc: 'Failed event messages', roles: ['ORG_ADMIN'] },
  { label: 'Emergency access', to: '/break-glass', icon: MedicalServicesOutlinedIcon, desc: 'Break the glass for a patient', roles: ['PROVIDER'] },
]

/** The post-login home: a Constellation hero band, real role-gated "at a glance" counts, and a role-aware launchpad. */
export function HomePage() {
  const { data: user } = useCurrentUser()
  const roles = user?.roles ?? []

  const statDefs = STAT_DEFS.filter((s) => hasAnyRole(roles, s.roles))
  const statResults = useQueries({
    queries: statDefs.map((s) => ({
      queryKey: ['dashboard-stat', s.key],
      queryFn: s.count,
      staleTime: 30_000,
      retry: false,
    })),
  })

  const tiles = TILES.filter((t) => hasAnyRole(roles, t.roles))

  if (!user) return null

  return (
    <Stack spacing={4}>
      {/* Constellation hero band — carries the signature motif from login into the app. */}
      <Box
        sx={{
          position: 'relative',
          overflow: 'hidden',
          borderRadius: 3,
          px: { xs: 3, md: 5 },
          py: { xs: 4, md: 5 },
          color: constellation.dark.text,
          background: `radial-gradient(900px 400px at 85% -20%, rgba(124,156,255,0.20), transparent 60%),
            radial-gradient(700px 380px at 0% 120%, rgba(94,234,212,0.16), transparent 55%),
            ${constellation.dark.bg}`,
        }}
      >
        <ConstellationBackground density={34} />
        <Box sx={{ position: 'relative', zIndex: 1 }}>
          <Typography variant="overline" sx={{ color: constellation.dark.teal, letterSpacing: '0.12em' }}>
            HealthCloud
          </Typography>
          <Typography
            component="h1"
            sx={{
              fontFamily: '"Space Grotesk", sans-serif',
              fontWeight: 700,
              fontSize: { xs: '1.75rem', md: '2.25rem' },
              letterSpacing: '-0.02em',
              mt: 0.5,
            }}
          >
            Welcome back, {user.fullName}
          </Typography>
          <Stack direction="row" spacing={1} sx={{ mt: 2, flexWrap: 'wrap', gap: 1, alignItems: 'center' }}>
            <Typography sx={{ color: constellation.dark.muted }}>
              {user.organizationName ?? 'No organization'}
            </Typography>
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
              {roles.map((role) => (
                <Chip
                  key={role}
                  label={role}
                  size="small"
                  variant="outlined"
                  sx={{ color: constellation.dark.text, borderColor: 'rgba(94,234,212,0.35)' }}
                />
              ))}
            </Box>
          </Stack>
        </Box>
      </Box>

      {/* At a glance — real, role-gated counts. */}
      {statDefs.length > 0 && (
        <Box>
          <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: '0.08em' }}>
            At a glance
          </Typography>
          <Box
            sx={{
              mt: 1,
              display: 'grid',
              gridTemplateColumns: { xs: 'repeat(2, 1fr)', sm: 'repeat(3, 1fr)', md: `repeat(${Math.min(statDefs.length, 6)}, 1fr)` },
              gap: 2,
            }}
          >
            {statDefs.map((s, i) => {
              const Icon = s.icon
              const r = statResults[i]
              const value = r?.isLoading || r?.isError || r?.data == null ? '—' : String(r.data)
              return (
                <Card key={s.key}>
                  <CardActionArea component={RouterLink} to={s.to} sx={{ p: 2, height: '100%' }}>
                    <Stack direction="row" spacing={1} sx={{ alignItems: 'center', color: 'text.secondary' }}>
                      <Icon fontSize="small" />
                      <Typography variant="body2">{s.label}</Typography>
                    </Stack>
                    <Typography sx={{ fontFamily: MONO, fontSize: 30, fontWeight: 600, mt: 1, color: 'text.primary' }}>
                      {value}
                    </Typography>
                  </CardActionArea>
                </Card>
              )
            })}
          </Box>
        </Box>
      )}

      {/* Quick access — role-aware launchpad. */}
      <Box>
        <Typography variant="overline" color="text.secondary" sx={{ letterSpacing: '0.08em' }}>
          Quick access
        </Typography>
        <Box
          sx={{
            mt: 1,
            display: 'grid',
            gridTemplateColumns: { xs: '1fr', sm: 'repeat(2, 1fr)', md: 'repeat(3, 1fr)' },
            gap: 2,
          }}
        >
          {tiles.map((t) => {
            const Icon = t.icon
            return (
              <Card key={t.to}>
                <CardActionArea component={RouterLink} to={t.to} sx={{ p: 2.5, height: '100%' }}>
                  <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
                    <Box
                      sx={{
                        width: 40,
                        height: 40,
                        borderRadius: 2,
                        display: 'grid',
                        placeItems: 'center',
                        bgcolor: 'rgba(13,148,136,0.10)',
                        color: 'primary.dark',
                        flexShrink: 0,
                      }}
                    >
                      <Icon fontSize="small" />
                    </Box>
                    <Box sx={{ minWidth: 0 }}>
                      <Typography sx={{ fontWeight: 600 }}>{t.label}</Typography>
                      <Typography variant="body2" color="text.secondary" noWrap>
                        {t.desc}
                      </Typography>
                    </Box>
                  </Stack>
                </CardActionArea>
              </Card>
            )
          })}
        </Box>
      </Box>
    </Stack>
  )
}
