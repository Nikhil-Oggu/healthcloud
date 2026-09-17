import axe from 'axe-core'
import { expect } from 'vitest'

/**
 * Run axe-core against a rendered DOM subtree and fail the test if it finds any WCAG A/AA violation
 * (§Phase 9 slice 10 — accessibility). This is our reusable accessibility gate for component/page tests, in
 * the spirit of the other small helpers in this project.
 *
 * <p><b>Honest scope (rules 2–3):</b> this is an <i>automated</i> check under jsdom, not a certification. axe-core
 * cannot evaluate <b>color contrast</b> without a real rendering engine, so that rule never runs here — contrast is
 * verified in the browser instead. A green result means "no automated WCAG A/AA violation was detected on this
 * markup", which is a real but partial signal. We scope to the WCAG 2.0/2.1/2.2 A+AA rule tags.
 *
 * @param container the DOM node to audit (e.g. the `container` returned by Testing Library's `render`)
 */
export async function expectNoAxeViolations(container: Element): Promise<void> {
  const results = await axe.run(container, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    // color-contrast needs a real rendering engine (it reads computed pixels via canvas), which jsdom lacks —
    // it can't run here, so disable it explicitly rather than let it throw canvas errors. Contrast is a browser check.
    rules: { 'color-contrast': { enabled: false } },
  })
  if (results.violations.length > 0) {
    const summary = results.violations
      .map((v) => {
        const nodes = v.nodes.map((n) => `      - ${n.html}`).join('\n')
        return `  • [${v.impact}] ${v.id}: ${v.help}\n    ${v.helpUrl}\n${nodes}`
      })
      .join('\n')
    expect.fail(`Found ${results.violations.length} accessibility violation(s):\n${summary}`)
  }
}
