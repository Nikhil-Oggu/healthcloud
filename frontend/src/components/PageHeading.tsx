import type { ReactNode } from 'react'
import { Typography } from '@mui/material'
import type { SxProps, Theme } from '@mui/material'

/**
 * The single top-level heading for a page (§Phase 9 slice 10 — accessibility). It keeps the existing `h5` visual
 * style but renders a semantic `<h1>` (`component="h1"`), so every route has exactly one first-level heading and a
 * correct heading order (WCAG 1.3.1 / 2.4.6). Card and section subheadings stay at their own lower levels.
 */
export function PageHeading({
  children,
  sx,
  gutterBottom,
}: {
  children: ReactNode
  sx?: SxProps<Theme>
  gutterBottom?: boolean
}) {
  return (
    <Typography variant="h5" component="h1" gutterBottom={gutterBottom} sx={sx}>
      {children}
    </Typography>
  )
}
