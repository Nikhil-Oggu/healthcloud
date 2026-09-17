import { Card, CardContent, Chip, Stack, Typography } from '@mui/material'
import { useCurrentUser } from '../auth/useAuth'
import { PageHeading } from '../components/PageHeading'

/** The first authenticated screen: shows the backend-derived identity for the signed-in user. */
export function HomePage() {
  const { data: user } = useCurrentUser()
  if (!user) {
    return null
  }

  return (
    <Card sx={{ maxWidth: 560 }}>
      <CardContent>
        <Typography variant="overline" color="text.secondary">
          Signed in as
        </Typography>
        <PageHeading gutterBottom>
          {user.fullName}
        </PageHeading>

        <Stack spacing={2} sx={{ mt: 1 }}>
          <Field label="Email" value={user.email} />
          <Field label="Organization" value={user.organizationName ?? '—'} />
          <div>
            <Typography variant="body2" color="text.secondary">
              Roles
            </Typography>
            <Stack direction="row" spacing={1} sx={{ mt: 0.5, flexWrap: 'wrap', rowGap: 1 }}>
              {user.roles.length > 0 ? (
                user.roles.map((role) => <Chip key={role} label={role} size="small" />)
              ) : (
                <Typography variant="body2">No roles</Typography>
              )}
            </Stack>
          </div>
        </Stack>
      </CardContent>
    </Card>
  )
}

function Field({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body1">{value}</Typography>
    </div>
  )
}
