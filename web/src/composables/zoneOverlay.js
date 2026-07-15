export function getContainMapping(cssW, cssH, srcW, srcH) {
  const scale = Math.min(cssW / srcW, cssH / srcH)
  const drawW = srcW * scale
  const drawH = srcH * scale
  const offsetX = (cssW - drawW) / 2
  const offsetY = (cssH - drawH) / 2
  return { scale, offsetX, offsetY, drawW, drawH }
}

export function screenToNorm(sx, sy, mapping, srcW, srcH) {
  const x = (sx - mapping.offsetX) / mapping.scale / srcW
  const y = (sy - mapping.offsetY) / mapping.scale / srcH
  return {
    x: Math.min(1, Math.max(0, x)),
    y: Math.min(1, Math.max(0, y))
  }
}

export function normToScreen(nx, ny, mapping, srcW, srcH) {
  return {
    x: mapping.offsetX + nx * srcW * mapping.scale,
    y: mapping.offsetY + ny * srcH * mapping.scale
  }
}

export function parseZonePoints(points) {
  if (!points) return []
  if (Array.isArray(points)) {
    return points
      .map((p) => {
        if (Array.isArray(p) && p.length >= 2) return { x: Number(p[0]), y: Number(p[1]) }
        if (p && typeof p === 'object') return { x: Number(p.x), y: Number(p.y) }
        return null
      })
      .filter((p) => p && Number.isFinite(p.x) && Number.isFinite(p.y))
  }
  if (typeof points === 'string') {
    try {
      return parseZonePoints(JSON.parse(points))
    } catch {
      return []
    }
  }
  return []
}

export function drawZones(ctx, zones, mapping, srcW, srcH, { draftPoints = [], hover = null } = {}) {
  ctx.save()
  for (const zone of zones) {
    const pts = parseZonePoints(zone.points)
    if (pts.length < 2) continue
    const screen = pts.map((p) => normToScreen(p.x, p.y, mapping, srcW, srcH))
    ctx.beginPath()
    screen.forEach((p, i) => (i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y)))
    if (pts.length >= 3) ctx.closePath()
    ctx.fillStyle = 'rgba(239, 68, 68, 0.18)'
    ctx.strokeStyle = '#ef4444'
    ctx.lineWidth = 2
    ctx.setLineDash([6, 4])
    ctx.fill()
    ctx.stroke()
    ctx.setLineDash([])
    const label = zone.name || '禁停区'
    ctx.fillStyle = '#ef4444'
    ctx.font = '12px sans-serif'
    ctx.fillText(label, screen[0].x + 4, screen[0].y + 14)
  }

  if (draftPoints.length) {
    const screen = draftPoints.map((p) => normToScreen(p.x, p.y, mapping, srcW, srcH))
    ctx.beginPath()
    screen.forEach((p, i) => (i === 0 ? ctx.moveTo(p.x, p.y) : ctx.lineTo(p.x, p.y)))
    if (hover) {
      const h = normToScreen(hover.x, hover.y, mapping, srcW, srcH)
      ctx.lineTo(h.x, h.y)
    }
    ctx.strokeStyle = '#f97316'
    ctx.lineWidth = 2
    ctx.setLineDash([4, 3])
    ctx.stroke()
    ctx.setLineDash([])
    for (const p of screen) {
      ctx.beginPath()
      ctx.arc(p.x, p.y, 4, 0, Math.PI * 2)
      ctx.fillStyle = '#f97316'
      ctx.fill()
    }
  }
  ctx.restore()
}

function boxIou(a, b) {
  const [ax1, ay1, ax2, ay2] = a
  const [bx1, by1, bx2, by2] = b
  const ix1 = Math.max(ax1, bx1)
  const iy1 = Math.max(ay1, by1)
  const ix2 = Math.min(ax2, bx2)
  const iy2 = Math.min(ay2, by2)
  const iw = Math.max(0, ix2 - ix1)
  const ih = Math.max(0, iy2 - iy1)
  const inter = iw * ih
  if (inter <= 0) return 0
  const areaA = Math.max(0, ax2 - ax1) * Math.max(0, ay2 - ay1)
  const areaB = Math.max(0, bx2 - bx1) * Math.max(0, by2 - by1)
  return inter / (areaA + areaB - inter || 1)
}

function plateBboxOnVehicle(pinfo, vehicleBbox) {
  const rel = pinfo._rel
  const pb = pinfo.bbox
  if (Array.isArray(rel) && rel.length >= 4) {
    const [vx1, vy1, vx2, vy2] = vehicleBbox
    const vw = Math.max(1, vx2 - vx1)
    const vh = Math.max(1, vy2 - vy1)
    const pcx = vx1 + rel[0] * vw
    const pcy = vy1 + rel[1] * vh
    const pw = rel[2] * vw
    const ph = rel[3] * vh
    return [
      Math.round(pcx - pw * 0.5),
      Math.round(pcy - ph * 0.5),
      Math.round(pcx + pw * 0.5),
      Math.round(pcy + ph * 0.5)
    ]
  }
  const oldVb = pinfo.vehicleBbox
  if (!Array.isArray(oldVb) || oldVb.length < 4 || !Array.isArray(pb) || pb.length < 4) {
    return Array.isArray(pb) ? pb.slice(0, 4) : [0, 0, 0, 0]
  }
  const ovw = Math.max(1, oldVb[2] - oldVb[0])
  const ovh = Math.max(1, oldVb[3] - oldVb[1])
  const derivedRel = [
    ((pb[0] + pb[2]) * 0.5 - oldVb[0]) / ovw,
    ((pb[1] + pb[3]) * 0.5 - oldVb[1]) / ovh,
    (pb[2] - pb[0]) / ovw,
    (pb[3] - pb[1]) / ovh
  ]
  return plateBboxOnVehicle({ ...pinfo, _rel: derivedRel }, vehicleBbox)
}

/** Keep plate boxes aligned with moving vehicle boxes (IoU match). */
export function syncPlateOverlays(plates, vehicles) {
  if (!plates?.length) return []
  if (!vehicles?.length) return plates.map((p) => ({ ...p }))
  const synced = []
  const used = new Set()
  for (const pinfo of plates) {
    const oldVb = pinfo.vehicleBbox
    if (!Array.isArray(oldVb) || oldVb.length < 4) {
      synced.push({ ...pinfo })
      continue
    }
    let bestI = -1
    let bestScore = 0
    for (let i = 0; i < vehicles.length; i++) {
      if (used.has(i)) continue
      const bb = vehicles[i].bbox
      if (!Array.isArray(bb) || bb.length < 4) continue
      const score = boxIou(oldVb, bb)
      if (score > bestScore) {
        bestScore = score
        bestI = i
      }
    }
    if (bestI < 0 || bestScore < 0.08) {
      synced.push({ ...pinfo })
      continue
    }
    used.add(bestI)
    const vb = vehicles[bestI].bbox.slice(0, 4).map((v) => Math.round(v))
    synced.push({
      ...pinfo,
      vehicleBbox: vb,
      bbox: plateBboxOnVehicle(pinfo, vb)
    })
  }
  return synced
}
