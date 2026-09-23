import { useState } from 'react'
import {
  AppBar,
  Box,
  Button,
  Chip,
  Container,
  Divider,
  Drawer,
  IconButton,
  Link,
  List,
  ListItem,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  ListSubheader,
  Toolbar,
  Typography,
  useMediaQuery,
} from '@mui/material'
import { useTheme } from '@mui/material/styles'
import MenuOutlinedIcon from '@mui/icons-material/MenuOutlined'
import SpaceDashboardOutlinedIcon from '@mui/icons-material/SpaceDashboardOutlined'
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
import LogoutOutlinedIcon from '@mui/icons-material/LogoutOutlined'
import type { SvgIconComponent } from '@mui/icons-material'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { useCurrentUser } from '../auth/useAuth'
import { Brand } from '../components/Brand'
import { ThemeToggle } from '../components/ThemeToggle'

const DRAWER_WIDTH = 260

type NavItem = { label: string; to: string; icon: SvgIconComponent; roles: string[] }
type NavGroup = { label: string; items: NavItem[] }

// Role-gated navigation, grouped by domain. Gating is for convenience only — the backend authorizes
// every operation regardless of what the UI shows.
const NAV_GROUPS: NavGroup[] = [
  {
    label: 'Care',
    items: [
      { label: 'Dashboard', to: '/', icon: SpaceDashboardOutlinedIcon, roles: ['PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'AUDITOR', 'ORG_ADMIN'] },
      { label: 'Patients', to: '/patients', icon: PeopleOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'] },
      { label: 'Requests', to: '/requests', icon: AssignmentOutlinedIcon, roles: ['PATIENT', 'PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'] },
      { label: 'Referrals', to: '/referrals', icon: CallSplitOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'ORG_ADMIN'] },
      { label: 'Emergency access', to: '/break-glass', icon: MedicalServicesOutlinedIcon, roles: ['PROVIDER'] },
    ],
  },
  {
    label: 'Claims & coverage',
    items: [
      { label: 'Claims', to: '/claims', icon: ReceiptLongOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
      { label: 'Coverage', to: '/coverage-plans', icon: ShieldOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
      { label: 'Prior auth', to: '/prior-authorizations', icon: FactCheckOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
      { label: 'Appeals', to: '/appeals', icon: GavelOutlinedIcon, roles: ['PROVIDER', 'CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
      { label: 'Reviews', to: '/claim-reviews', icon: RateReviewOutlinedIcon, roles: ['CARE_COORDINATOR', 'CLAIMS_REVIEWER', 'ORG_ADMIN'] },
      { label: 'Reprocessing', to: '/reprocessing', icon: AutorenewOutlinedIcon, roles: ['CLAIMS_REVIEWER', 'ORG_ADMIN'] },
    ],
  },
  {
    label: 'Governance',
    items: [
      { label: 'Audit', to: '/audit', icon: HistoryEduOutlinedIcon, roles: ['AUDITOR', 'ORG_ADMIN'] },
      { label: 'Access review', to: '/access-review', icon: ManageAccountsOutlinedIcon, roles: ['AUDITOR', 'ORG_ADMIN'] },
      { label: 'Dead letters', to: '/dead-letters', icon: ReportProblemOutlinedIcon, roles: ['ORG_ADMIN'] },
    ],
  },
]

function isActivePath(pathname: string, to: string): boolean {
  if (to === '/') return pathname === '/'
  return pathname === to || pathname.startsWith(to + '/')
}

/**
 * The authenticated shell: a grouped, role-aware sidebar (the "Primary" nav landmark), a brand mark,
 * and a user/logout footer. On desktop the sidebar is permanent; on mobile it's a drawer behind a
 * hamburger. Nav is gated by role for convenience only — the backend still authorizes every operation.
 */
export function AppLayout() {
  const { data: user } = useCurrentUser()
  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const theme = useTheme()
  // defaultMatches:true → the permanent sidebar renders in jsdom tests (no matchMedia there), so the
  // single "Primary" nav landmark the accessibility test asserts is present.
  const isDesktop = useMediaQuery(theme.breakpoints.up('md'), { defaultMatches: true })
  const [mobileOpen, setMobileOpen] = useState(false)

  const roles = user?.roles ?? []

  async function handleLogout() {
    // Fetch the Cognito RP-initiated logout URL (if any) before ending the session. /auth/config is public,
    // so this works regardless of order; any failure just falls back to a local-only logout.
    let cognitoLogoutUrl: string | null | undefined
    try {
      cognitoLogoutUrl = (await api.authConfig()).cognitoLogoutUrl
    } catch {
      // ignore — Cognito may not be configured (dev), or the probe failed; fall back to local logout below.
    }
    try {
      await api.logout()
    } finally {
      queryClient.clear()
      if (cognitoLogoutUrl) {
        // Full-page redirect through Cognito's hosted-UI /logout to clear its SSO cookie, then back to the app
        // (which lands unauthenticated on /login). Without this, Cognito silently re-authenticates the same user.
        window.location.assign(cognitoLogoutUrl)
      } else {
        navigate('/login', { replace: true })
      }
    }
  }

  function go(to: string) {
    navigate(to)
    if (!isDesktop) setMobileOpen(false)
  }

  const drawer = (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Box sx={{ px: 2.5, py: 2.25 }}>
        <Brand />
      </Box>
      <Divider />

      <Box
        component="nav"
        aria-label="Primary"
        sx={{ flexGrow: 1, overflowY: 'auto', px: 1.25, py: 1 }}
      >
        {NAV_GROUPS.map((group) => {
          const items = group.items.filter((item) => roles.some((r) => item.roles.includes(r)))
          if (items.length === 0) return null
          return (
            <List
              key={group.label}
              disablePadding
              sx={{ mb: 1 }}
              subheader={
                <ListSubheader
                  disableSticky
                  sx={{
                    bgcolor: 'transparent',
                    color: 'text.secondary',
                    fontSize: 11,
                    fontWeight: 700,
                    letterSpacing: '0.08em',
                    textTransform: 'uppercase',
                    lineHeight: 2.4,
                  }}
                >
                  {group.label}
                </ListSubheader>
              }
            >
              {items.map((item) => {
                const active = isActivePath(location.pathname, item.to)
                const Icon = item.icon
                return (
                  <ListItem key={item.to} disablePadding>
                    <ListItemButton
                      selected={active}
                      onClick={() => go(item.to)}
                      sx={{
                        borderRadius: 2,
                        mb: 0.25,
                        py: 0.9,
                        '& .MuiListItemIcon-root': { minWidth: 38, color: 'text.secondary' },
                        '&.Mui-selected': {
                          bgcolor: 'rgba(13,148,136,0.10)',
                          color: 'primary.dark',
                          fontWeight: 600,
                          '& .MuiListItemIcon-root': { color: 'primary.dark' },
                          '&:hover': { bgcolor: 'rgba(13,148,136,0.16)' },
                        },
                      }}
                    >
                      <ListItemIcon>
                        <Icon fontSize="small" />
                      </ListItemIcon>
                      <ListItemText
                        primary={item.label}
                        slotProps={{ primary: { sx: { fontSize: 14, fontWeight: active ? 600 : 500 } } }}
                      />
                    </ListItemButton>
                  </ListItem>
                )
              })}
            </List>
          )
        })}
      </Box>

      {user && (
        <>
          <Divider />
          <Box sx={{ p: 2 }}>
            <Typography variant="body2" sx={{ fontWeight: 600, lineHeight: 1.3 }} noWrap>
              {user.fullName ?? user.email}
            </Typography>
            <Typography variant="caption" color="text.secondary" noWrap sx={{ display: 'block' }}>
              {user.organizationName ?? 'No organization'}
            </Typography>
            <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap', mt: 1 }}>
              {roles.map((role) => (
                <Chip key={role} label={role} size="small" variant="outlined" />
              ))}
            </Box>
            <Button
              fullWidth
              variant="outlined"
              color="inherit"
              startIcon={<LogoutOutlinedIcon />}
              onClick={handleLogout}
              sx={{ mt: 1.5, justifyContent: 'flex-start' }}
            >
              Log out
            </Button>
            <Box sx={{ mt: 1.5 }}>
              <ThemeToggle />
            </Box>
          </Box>
        </>
      )}
    </Box>
  )

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: 'background.default' }}>
      {/* Skip link (WCAG 2.4.1): the first focusable element, hidden until focused. */}
      <Link
        href="#main"
        sx={{
          position: 'absolute',
          left: 8,
          top: -40,
          zIndex: (t) => t.zIndex.tooltip + 1,
          px: 2,
          py: 1,
          borderRadius: 1,
          bgcolor: 'background.paper',
          color: 'text.primary',
          boxShadow: 3,
          '&:focus': { top: 8 },
        }}
      >
        Skip to main content
      </Link>

      {/* Mobile top bar: hamburger + brand (desktop uses the permanent sidebar instead). */}
      {!isDesktop && (
        <AppBar position="fixed" sx={{ zIndex: (t) => t.zIndex.drawer + 1 }}>
          <Toolbar>
            <IconButton
              edge="start"
              aria-label="Open navigation"
              onClick={() => setMobileOpen(true)}
              sx={{ mr: 1.5 }}
            >
              <MenuOutlinedIcon />
            </IconButton>
            <Brand />
          </Toolbar>
        </AppBar>
      )}

      <Drawer
        variant={isDesktop ? 'permanent' : 'temporary'}
        open={isDesktop ? true : mobileOpen}
        onClose={() => setMobileOpen(false)}
        ModalProps={{ keepMounted: true }}
        sx={{
          width: isDesktop ? DRAWER_WIDTH : undefined,
          flexShrink: 0,
          '& .MuiDrawer-paper': {
            width: DRAWER_WIDTH,
            boxSizing: 'border-box',
            borderRight: '1px solid',
            borderColor: 'divider',
            bgcolor: 'background.paper',
          },
        }}
      >
        {drawer}
      </Drawer>

      <Box
        component="main"
        id="main"
        tabIndex={-1}
        sx={{ flexGrow: 1, minWidth: 0, outline: 'none' }}
      >
        {!isDesktop && <Toolbar />}
        <Container sx={{ py: 4 }}>
          <Outlet />
        </Container>
      </Box>
    </Box>
  )
}
