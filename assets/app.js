const key = new URLSearchParams(location.search).get('key') || ''
const $ = id => document.getElementById(id)
const plan = $('plan'), profile = $('heightChart'), signalChart = $('signalChart')
const points = []
const sampleTimes = new Set()
let origin = null, latest = null, latestLocated = null, socket = null, follow = true, pxPerMeter = 8, centerX = 0, centerY = 0
let manualOrigin = false
try {
  const saved = JSON.parse(localStorage.getItem('wifiSignalOrigin') || 'null')
  if (saved && Number.isFinite(saved.lat) && Number.isFinite(saved.lon)) { origin = saved; manualOrigin = true }
} catch (_) { /* 忽略损坏的本机设置 */ }
let lastSampleAt = 0, drawQueued = false

function tone(rssi) {
  if (rssi >= -55) return '#26dbab'
  if (rssi >= -67) return '#91de72'
  if (rssi >= -75) return '#ffd15a'
  if (rssi >= -85) return '#ff925a'
  return '#ed607e'
}
function relative(p) {
  if (!origin) return { x: 0, y: 0 }
  const rad = Math.PI / 180
  return {
    x: (p.lon - origin.lon) * 111320 * Math.cos(origin.lat * rad),
    y: (p.lat - origin.lat) * 111132
  }
}
function meters(value, uncertainty) { return `${value >= 0 ? '+' : ''}${value.toFixed(uncertainty < 3 ? 1 : 0)} m` }
function hasPosition(p) { return p && Number.isFinite(p.lat) && Number.isFinite(p.lon) && Number.isFinite(p.accuracy) }
function precisionLabel(p) {
  if (!hasPosition(p)) return '等待定位'
  if (p.accuracy <= 3) return '精细'
  if (p.accuracy <= 10) return '较好'
  if (p.accuracy <= 30) return '粗略'
  return '低精度'
}
function updateOriginInfo() {
  $('originInfo').textContent = origin ? `原点：${manualOrigin ? '手动设置' : '首个定位点'} · ${origin.source || '定位'} · 估计精度约 ±${Number(origin.accuracy).toFixed(1)} m` : '原点：等待首个位置'
}
function updateRelativePosition() {
  const pos = latestLocated ? relative(latestLocated) : null
  const uncertainty = pos ? Math.hypot(origin?.accuracy || 0, latestLocated.accuracy) : null
  $('relative').textContent = pos ? `东约 ${meters(pos.x, uncertainty)} · 北约 ${meters(pos.y, uncertainty)} · 相对误差约 ±${uncertainty.toFixed(0)} m` : '等待定位'
  $('accuracy').textContent = latestLocated ? `约 ±${latestLocated.accuracy.toFixed(1)} m` : '等待定位'
  return pos
}
function add(sample) {
  if (!sample || !sample.time || !Number.isFinite(sample.rssi) || sampleTimes.has(sample.time)) return
  sampleTimes.add(sample.time)
  if (hasPosition(sample)) {
    if (!latestLocated || sample.time >= latestLocated.time) latestLocated = sample
    if (!origin || (!manualOrigin && sample.time < origin.time)) {
      origin = sample
      pxPerMeter = Math.min(8, Math.max(.25, 90 / Math.max(10, sample.accuracy)))
      updateOriginInfo()
    }
  }
  points.push(sample)
  if (latest && sample.time < latest.time) points.sort((a, b) => a.time.localeCompare(b.time))
  if (points.length > 3000) sampleTimes.delete(points.shift().time)
  if (latest && sample.time < latest.time) { queueDraw(); return }
  latest = sample
  lastSampleAt = Date.parse(sample.time) || Date.now()
  const pos = updateRelativePosition()
  if (follow && pos) { centerX = pos.x; centerY = pos.y }
  $('signal').textContent = `${sample.rssi} dBm`
  const reliableHeight = sample.source === 'GPS' && typeof sample.altitude === 'number' && typeof sample.verticalAccuracy === 'number' && sample.verticalAccuracy <= 20
  const height = reliableHeight ? ` · GPS 高度 ${sample.altitude.toFixed(1)} m ±${sample.verticalAccuracy.toFixed(1)} m` : ''
  const locationAge = latestLocated ? Math.max(0, Math.round((Date.parse(sample.time) - (latestLocated.locationFixTime || Date.parse(latestLocated.time))) / 1000)) : null
  $('meta').textContent = `${sample.ssid || '当前 Wi‑Fi'} · ${new Date(sample.time).toLocaleTimeString()} · RSSI 每 0.5 秒读取。${latestLocated ? `定位：${latestLocated.source}，${precisionLabel(latestLocated)}，最近定位约 ${locationAge} 秒前；误差半径内的具体位置未知。` : '等待定位；信号曲线仍正常采集。'}${height}`
  queueDraw()
}
function fitted(canvas) {
  const rect = canvas.getBoundingClientRect()
  const ratio = Math.min(devicePixelRatio || 1, 2)
  const w = Math.max(1, Math.round(rect.width * ratio)), h = Math.max(1, Math.round(rect.height * ratio))
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h }
  const ctx = canvas.getContext('2d'); ctx.setTransform(ratio, 0, 0, ratio, 0, 0)
  return { ctx, w: rect.width, h: rect.height }
}
function niceStep(raw) {
  const base = Math.pow(10, Math.floor(Math.log10(Math.max(raw, .001))))
  const n = raw / base
  return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 5 ? 5 : 10) * base
}
function drawSignal(ctx, w, h, now = Date.now()) {
  ctx.save(); ctx.beginPath(); ctx.rect(0, 0, w, h); ctx.clip()
  ctx.fillStyle = '#0b1824'; ctx.fillRect(0, 0, w, h)
  const left = 43, right = w - 13, top = 13, bottom = h - 30
  const x = time => left + (time - (now - 60000)) / 60000 * (right - left)
  const y = rssi => bottom - (Math.max(-100, Math.min(-30, rssi)) + 100) / 70 * (bottom - top)
  ctx.font = '11px sans-serif'; ctx.fillStyle = '#a9c0ce'; ctx.textBaseline = 'middle'
  for (let value = -100; value <= -30; value += 10) {
    const py = y(value); ctx.strokeStyle = '#294353'; ctx.lineWidth = 1
    ctx.beginPath(); ctx.moveTo(left, py); ctx.lineTo(right, py); ctx.stroke()
    ctx.fillText(`${value}`, 4, py)
  }
  const firstTick = Math.ceil((now - 60000) / 10000) * 10000
  for (let tick = firstTick; tick <= now; tick += 10000) {
    const px = x(tick); ctx.strokeStyle = '#243d4d'; ctx.beginPath(); ctx.moveTo(px, top); ctx.lineTo(px, bottom); ctx.stroke()
    ctx.fillStyle = '#a9c0ce'; ctx.textAlign = 'center'; ctx.fillText(new Date(tick).toLocaleTimeString('zh-CN', { hour12: false }), px, h - 13)
  }
  ctx.textAlign = 'left'
  ctx.fillStyle = '#a9c0ce'; ctx.font = '10px sans-serif'; ctx.textBaseline = 'top'; ctx.fillText('RSSI / dBm · 最近 60 秒', left + 6, top + 4)
  const visible = points.filter(p => { const t = Date.parse(p.time); return t >= now - 60000 && t <= now && Number.isFinite(p.rssi) })
  if (!visible.length) { ctx.fillStyle = '#a9c0ce'; ctx.fillText('等待 Wi‑Fi 信号采样…', left + 12, h / 2); ctx.restore(); return }
  ctx.strokeStyle = '#34d5b2'; ctx.lineWidth = 2.5; ctx.lineJoin = 'round'; ctx.beginPath()
  let previous = null
  for (const p of visible) {
    const t = Date.parse(p.time), px = x(t), py = y(p.rssi)
    if (previous === null || t - previous > 2500) ctx.moveTo(px, py)
    else ctx.lineTo(px, py)
    previous = t
  }
  ctx.stroke()
  const last = visible[visible.length - 1], lastX = x(Date.parse(last.time)), lastY = y(last.rssi)
  ctx.fillStyle = tone(last.rssi); ctx.beginPath(); ctx.arc(lastX, lastY, 5, 0, Math.PI * 2); ctx.fill()
  ctx.fillStyle = '#dcecf7'; ctx.font = 'bold 12px sans-serif'; ctx.fillText(`${last.rssi} dBm`, Math.min(lastX + 8, right - 72), Math.max(15, lastY - 12))
  ctx.restore()
}
function drawPlan(ctx, w, h) {
  ctx.save(); ctx.beginPath(); ctx.rect(0, 0, w, h); ctx.clip()
  ctx.fillStyle = '#0b1824'; ctx.fillRect(0, 0, w, h)
  const sx = x => w / 2 + (x - centerX) * pxPerMeter
  const sy = y => h / 2 - (y - centerY) * pxPerMeter
  const step = niceStep(85 / pxPerMeter), left = centerX - w / 2 / pxPerMeter, right = centerX + w / 2 / pxPerMeter
  const bottom = centerY - h / 2 / pxPerMeter, top = centerY + h / 2 / pxPerMeter
  ctx.font = '11px sans-serif'; ctx.textBaseline = 'top'
  for (let x = Math.ceil(left / step) * step; x <= right; x += step) {
    const px = sx(x); ctx.strokeStyle = Math.abs(x) < step / 10 ? '#40708a' : '#263d4e'; ctx.lineWidth = Math.abs(x) < step / 10 ? 2 : 1
    ctx.beginPath(); ctx.moveTo(px, 0); ctx.lineTo(px, h); ctx.stroke()
    ctx.fillStyle = '#819ba9'; ctx.fillText(`${Math.round(x * 10) / 10}m`, px + 3, 7)
  }
  for (let y = Math.ceil(bottom / step) * step; y <= top; y += step) {
    const py = sy(y); ctx.strokeStyle = Math.abs(y) < step / 10 ? '#40708a' : '#263d4e'; ctx.lineWidth = Math.abs(y) < step / 10 ? 2 : 1
    ctx.beginPath(); ctx.moveTo(0, py); ctx.lineTo(w, py); ctx.stroke()
    ctx.fillStyle = '#819ba9'; ctx.fillText(`${Math.round(y * 10) / 10}m`, 6, py + 2)
  }
  ctx.fillStyle = '#b6cbd6'; ctx.font = 'bold 13px sans-serif'; ctx.fillText('北 ↑', w - 58, 12)
  if (!origin) { ctx.fillStyle = '#a9c0cc'; ctx.font = '16px sans-serif'; ctx.textAlign = 'center'; ctx.fillText('等待第一个定位点', w / 2, h / 2); ctx.font = '12px sans-serif'; ctx.fillText('Wi‑Fi 信号曲线继续实时采集', w / 2, h / 2 + 25); ctx.textAlign = 'left'; ctx.restore(); return }
  const mapped = []
  for (const p of points) {
    if (!hasPosition(p)) continue
    const last = mapped[mapped.length - 1]
    if (last && ((p.locationFixTime && p.locationFixTime === last.locationFixTime && p.source === last.source) || (p.lat === last.lat && p.lon === last.lon))) mapped[mapped.length - 1] = p
    else mapped.push(p)
  }
  ctx.strokeStyle = 'rgba(181,217,238,.3)'; ctx.lineWidth = 1.5; ctx.beginPath()
  mapped.forEach((p, i) => {
    const r = relative(p); i ? ctx.lineTo(sx(r.x), sy(r.y)) : ctx.moveTo(sx(r.x), sy(r.y))
  })
  ctx.stroke()
  for (const p of mapped) {
    const r = relative(p), x = sx(r.x), y = sy(r.y)
    if (x < -20 || x > w + 20 || y < -20 || y > h + 20) continue
    const alpha = p.accuracy <= 3 ? .85 : p.accuracy <= 10 ? .65 : p.accuracy <= 30 ? .45 : .25
    ctx.globalAlpha = alpha; ctx.fillStyle = tone(p.rssi)
    ctx.beginPath(); ctx.arc(x, y, p.accuracy <= 10 ? 6 : 4, 0, Math.PI * 2); ctx.fill(); ctx.globalAlpha = 1
  }
  const zeroX = sx(0), zeroY = sy(0)
  if (Number.isFinite(origin.accuracy)) {
    ctx.strokeStyle = '#ffd15a88'; ctx.lineWidth = 1; ctx.setLineDash([3, 5]); ctx.beginPath(); ctx.arc(zeroX, zeroY, Math.min(origin.accuracy * pxPerMeter, 5000), 0, Math.PI * 2); ctx.stroke(); ctx.setLineDash([])
  }
  ctx.strokeStyle = '#ffd15a'; ctx.lineWidth = 2; ctx.beginPath(); ctx.moveTo(zeroX - 8, zeroY); ctx.lineTo(zeroX + 8, zeroY); ctx.moveTo(zeroX, zeroY - 8); ctx.lineTo(zeroX, zeroY + 8); ctx.stroke()
  ctx.fillStyle = '#ffd15a'; ctx.fillText('原点', zeroX + 10, zeroY - 14)
  const visibleLatest = latestLocated
  if (visibleLatest) {
    const r = relative(visibleLatest), x = sx(r.x), y = sy(r.y)
    ctx.strokeStyle = '#75b8fa'; ctx.lineWidth = 1.5; ctx.setLineDash([5, 5]); ctx.beginPath(); ctx.arc(x, y, Math.min(visibleLatest.accuracy * pxPerMeter, 5000), 0, Math.PI * 2); ctx.stroke(); ctx.setLineDash([])
    ctx.fillStyle = '#278ef4'; ctx.strokeStyle = '#fff'; ctx.lineWidth = 3; ctx.beginPath(); ctx.arc(x, y, 11, 0, Math.PI * 2); ctx.fill(); ctx.stroke()
  }
  ctx.fillStyle = '#d4e6f0'; ctx.font = '12px sans-serif'; ctx.fillText(`一格 ${step} m · ${precisionLabel(latestLocated)}定位 ±${latestLocated?.accuracy.toFixed(1) || '—'} m`, 12, h - 35)
  const levels = [-90, -80, -70, -60, -50]
  levels.forEach((level, i) => { ctx.fillStyle = tone(level); ctx.fillRect(12 + i * 44, h - 19, 34, 6) })
  ctx.fillStyle = '#b7cbd7'; ctx.fillText('弱', 12, h - 8); ctx.fillText('强', 220, h - 8)
  ctx.restore()
}
function drawHeight(ctx, w, h) {
  ctx.fillStyle = '#0b1824'; ctx.fillRect(0, 0, w, h)
  const valid = points.filter(p => p.source === 'GPS' && typeof p.altitude === 'number' && Number.isFinite(p.altitude) && typeof p.verticalAccuracy === 'number' && p.verticalAccuracy <= 20)
  ctx.fillStyle = '#b3c9d6'; ctx.font = '13px sans-serif'; ctx.fillText('GPS 高度剖面 · 横轴为行走距离，颜色为 RSSI', 10, 17)
  if (!valid.length) { ctx.fillText('等待可靠的 GPS 高度数据', 10, h / 2); return }
  const values = valid.map(p => p.altitude), min = Math.min(...values), max = Math.max(...values)
  const lo = min - Math.max(1, (max - min) * .2), hi = max + Math.max(1, (max - min) * .2)
  const distances = [0]
  for (let i = 1; i < valid.length; i++) {
    const a = relative(valid[i - 1]), b = relative(valid[i]); distances.push(distances.at(-1) + Math.hypot(b.x - a.x, b.y - a.y))
  }
  const total = Math.max(10, distances.at(-1)), x = d => 56 + d / total * (w - 72), y = a => h - 25 - (a - lo) / (hi - lo) * (h - 60)
  ctx.strokeStyle = '#35576a'; ctx.beginPath(); ctx.moveTo(56, 35); ctx.lineTo(56, h - 25); ctx.lineTo(w - 10, h - 25); ctx.stroke()
  ctx.fillStyle = '#a8c0cd'; ctx.font = '11px sans-serif'; ctx.fillText(`${hi.toFixed(1)}m`, 4, 40); ctx.fillText(`${lo.toFixed(1)}m`, 4, h - 27); ctx.fillText(`${distances.at(-1).toFixed(1)}m`, w - 60, h - 8)
  ctx.strokeStyle = '#9cbed0'; ctx.lineWidth = 2; ctx.beginPath()
  valid.forEach((p, i) => i ? ctx.lineTo(x(distances[i]), y(p.altitude)) : ctx.moveTo(x(0), y(p.altitude)))
  ctx.stroke()
  valid.forEach((p, i) => { ctx.fillStyle = tone(p.rssi); ctx.beginPath(); ctx.arc(x(distances[i]), y(p.altitude), 4, 0, Math.PI * 2); ctx.fill() })
}
function queueDraw() {
  if (drawQueued) return
  drawQueued = true
  requestAnimationFrame(() => {
    drawQueued = false
    const s = fitted(signalChart); drawSignal(s.ctx, s.w, s.h)
    const p = fitted(plan); drawPlan(p.ctx, p.w, p.h)
    if ($('heightToggle').checked) { const h = fitted(profile); drawHeight(h.ctx, h.w, h.h) }
  })
}
fetch('/history?key=' + encodeURIComponent(key)).then(r => r.json()).then(data => {
  for (const sample of data.samples || []) add(sample)
  queueDraw()
}).catch(() => { $('state').textContent = '历史读取失败' })
function connect() {
  const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:'
  socket = new WebSocket(`${scheme}//${location.host}/ws?key=${encodeURIComponent(key)}`)
  socket.onopen = () => { $('state').textContent = '已连接，等待采样' }
  socket.onmessage = event => {
    const message = JSON.parse(event.data)
    if (message.type === 'sample') { add(message.sample); $('state').textContent = '实时采集中' }
  }
  socket.onclose = () => { $('state').textContent = '重连中…'; setTimeout(connect, 2000) }
}
connect()
setInterval(() => { if (socket?.readyState === WebSocket.OPEN && (!lastSampleAt || Date.now() - lastSampleAt > 15000)) $('state').textContent = '已连接，等待采样' }, 5000)
function zoom(multiplier) { pxPerMeter = Math.max(.2, Math.min(100, pxPerMeter * multiplier)); queueDraw() }
$('zoomIn').onclick = () => zoom(1.5)
$('zoomOut').onclick = () => zoom(1 / 1.5)
$('follow').onclick = () => { follow = true; if (latestLocated) { const r = relative(latestLocated); centerX = r.x; centerY = r.y } queueDraw() }
$('setOrigin').onclick = () => {
  if (!latestLocated) return alert('尚无定位点，无法设置原点')
  origin = { lat: latestLocated.lat, lon: latestLocated.lon, accuracy: latestLocated.accuracy, source: latestLocated.source, time: latestLocated.time }
  manualOrigin = true
  localStorage.setItem('wifiSignalOrigin', JSON.stringify(origin))
  updateRelativePosition()
  if (follow) { const r = relative(latestLocated); centerX = r.x; centerY = r.y }
  updateOriginInfo(); queueDraw()
}
$('resetOrigin').onclick = () => {
  manualOrigin = false
  localStorage.removeItem('wifiSignalOrigin')
  origin = points.find(hasPosition) || null
  if (origin) pxPerMeter = Math.min(8, Math.max(.25, 90 / Math.max(10, origin.accuracy)))
  updateRelativePosition()
  if (follow && latestLocated) { const r = relative(latestLocated); centerX = r.x; centerY = r.y }
  updateOriginInfo(); queueDraw()
}
$('heightToggle').onchange = event => { $('heightPanel').classList.toggle('visible', event.target.checked); queueDraw() }
$('export').onclick = () => {
  if (!points.length) return alert('还没有采样数据')
  const showHeight = $('heightToggle').checked
  const canvas = document.createElement('canvas'); canvas.width = 1400; canvas.height = showHeight ? 1410 : 1190
  const ctx = canvas.getContext('2d'); ctx.fillStyle = '#09131d'; ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = '#edf8ff'; ctx.font = 'bold 30px sans-serif'; ctx.fillText('WiFi 信号探测器', 35, 42)
  ctx.font = '17px sans-serif'; ctx.fillText(`当前 RSSI ${latest.rssi} dBm · 定位精度 ${latestLocated ? `约 ±${latestLocated.accuracy.toFixed(1)} m` : '未取得'} · ${points.length} 个采样点`, 35, 69)
  ctx.save(); ctx.translate(25, 85); drawSignal(ctx, 1350, 260); ctx.restore()
  ctx.save(); ctx.translate(25, 365); drawPlan(ctx, 1350, 770); ctx.restore()
  if (showHeight) { ctx.save(); ctx.translate(25, 1155); drawHeight(ctx, 1350, 210); ctx.restore() }
  const png = canvas.toDataURL('image/png')
  if (window.AndroidBridge) window.AndroidBridge.savePng(png)
  else { const a = document.createElement('a'); a.href = png; a.download = 'wifi-signal-relative.png'; a.click() }
}
let drag = null
plan.addEventListener('pointerdown', event => { drag = { x: event.clientX, y: event.clientY }; plan.setPointerCapture(event.pointerId) })
plan.addEventListener('pointermove', event => {
  if (!drag) return
  centerX -= (event.clientX - drag.x) / pxPerMeter; centerY += (event.clientY - drag.y) / pxPerMeter
  drag = { x: event.clientX, y: event.clientY }; follow = false; queueDraw()
})
plan.addEventListener('pointerup', () => { drag = null })
plan.addEventListener('wheel', event => { event.preventDefault(); zoom(event.deltaY < 0 ? 1.2 : 1 / 1.2) }, { passive: false })
addEventListener('resize', queueDraw)
setInterval(queueDraw, 500)
updateOriginInfo()
queueDraw()
