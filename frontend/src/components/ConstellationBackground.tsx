import { useEffect, useRef } from 'react'

type Node = { x: number; y: number; vx: number; vy: number; r: number }

/**
 * The "Care Constellation" signature motif: a living network of nodes (patients ↔ providers ↔
 * coordinators) drawn on a canvas that fills its positioned parent. Purely decorative (`aria-hidden`),
 * resize-aware, and **static when the viewer prefers reduced motion**. Reused by the login hero and
 * the dashboard hero band. See docs/design/design-system.md §6.
 */
export function ConstellationBackground({ density = 26 }: { density?: number }) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    const parent = canvas?.parentElement
    const ctx = canvas?.getContext('2d')
    // jsdom has no 2d canvas — bail cleanly so tests just render the element.
    if (!canvas || !parent || !ctx) return

    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
    let nodes: Node[] = []
    let raf = 0

    function fit() {
      const rect = parent!.getBoundingClientRect()
      const dpr = Math.min(window.devicePixelRatio || 1, 2)
      canvas!.width = Math.max(1, Math.floor(rect.width * dpr))
      canvas!.height = Math.max(1, Math.floor(rect.height * dpr))
      canvas!.style.width = `${rect.width}px`
      canvas!.style.height = `${rect.height}px`
      ctx!.setTransform(dpr, 0, 0, dpr, 0, 0)
      return rect
    }

    function build() {
      const rect = fit()
      const count = Math.min(72, Math.round(rect.width / density))
      nodes = Array.from({ length: count }, () => ({
        x: Math.random() * rect.width,
        y: Math.random() * rect.height,
        vx: (Math.random() - 0.5) * 0.35,
        vy: (Math.random() - 0.5) * 0.35,
        r: Math.random() * 1.8 + 1,
      }))
    }

    function draw() {
      const w = canvas!.clientWidth
      const h = canvas!.clientHeight
      ctx!.clearRect(0, 0, w, h)

      // Connecting lines between nearby nodes.
      for (let i = 0; i < nodes.length; i++) {
        const a = nodes[i]
        for (let j = i + 1; j < nodes.length; j++) {
          const b = nodes[j]
          const dx = a.x - b.x
          const dy = a.y - b.y
          const d = Math.hypot(dx, dy)
          if (d < 130) {
            ctx!.strokeStyle = `rgba(124,156,255,${(1 - d / 130) * 0.4})`
            ctx!.lineWidth = 0.6
            ctx!.beginPath()
            ctx!.moveTo(a.x, a.y)
            ctx!.lineTo(b.x, b.y)
            ctx!.stroke()
          }
        }
      }

      // Glowing nodes.
      for (const p of nodes) {
        const g = ctx!.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.r * 3)
        g.addColorStop(0, 'rgba(94,234,212,0.9)')
        g.addColorStop(1, 'rgba(94,234,212,0)')
        ctx!.fillStyle = g
        ctx!.beginPath()
        ctx!.arc(p.x, p.y, p.r * 3, 0, Math.PI * 2)
        ctx!.fill()
        if (!reduce) {
          p.x += p.vx
          p.y += p.vy
          if (p.x < 0 || p.x > w) p.vx *= -1
          if (p.y < 0 || p.y > h) p.vy *= -1
        }
      }

      if (!reduce) raf = requestAnimationFrame(draw)
    }

    build()
    draw()

    const onResize = () => {
      build()
      if (reduce) draw()
    }
    window.addEventListener('resize', onResize)
    return () => {
      cancelAnimationFrame(raf)
      window.removeEventListener('resize', onResize)
    }
  }, [density])

  return (
    <canvas
      ref={canvasRef}
      aria-hidden
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', opacity: 0.55 }}
    />
  )
}
