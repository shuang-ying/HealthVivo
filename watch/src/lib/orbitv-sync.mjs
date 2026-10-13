export async function syncOrbitV(client, archive, hooks) {
  let request = { known: archive.versions() }; let changed = 0; let pages = 0
  while (true) {
    const response = await client.http.request('/api/sync', { method: 'POST', data: JSON.stringify(request), header: { 'Content-Type': 'application/json' }, responseType: 'json', retries: 1 })
    if (!hooks.current()) return null
    const packet = response.data
    if (!packet || packet.schema !== 3 || typeof packet.sessionId !== 'string' || packet.sessionId.length > 80 ||
        typeof packet.receiptId !== 'string' || packet.receiptId.length > 80 || !Array.isArray(packet.days) || packet.days.length > 12 ||
        !Number.isSafeInteger(packet.command) || packet.command < 0 || !Number.isSafeInteger(packet.cursor) || packet.cursor < 0 ||
        typeof packet.more !== 'boolean' || (packet.more && (!packet.days.length || packet.cursor <= (request.cursor || 0)))) throw new Error('同步回应格式无效')
    changed += await archive.merge(packet.days)
    if (!hooks.current()) return null
    await client.http.request('/api/saved', { method: 'POST', data: JSON.stringify({ receiptId: packet.receiptId, days: packet.days.map(day => ({ day: day.day, revision: day.revision })) }),
      header: { 'Content-Type': 'application/json' }, responseType: 'json', retries: 1 })
    if (!hooks.current()) return null
    if (hooks.progress) hooks.progress(changed)
    if (!packet.more) { await archive.markCommand(packet.command); return { changed } }
    if (++pages > 5000) throw new Error('同步分页异常')
    request = { sessionId: packet.sessionId, cursor: packet.cursor }
  }
}
