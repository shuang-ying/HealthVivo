import dashboard from './dashboard.mjs'
const ROOT = 'fitness.index.v3'
const LEGACY = 'fitness.dashboard.v2'
const PREFIX = 'fitness.day.v3.'
function validDay(day) { return /^\d{4}-\d{2}-\d{2}$/.test(day) }
function io(storage, method, options) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('手表存储超时')), 10000)
    storage[method]({ ...options, success: value => { clearTimeout(timeout); resolve(value) }, fail: () => { clearTimeout(timeout); reject(new Error('手表本地存储失败')) } })
  })
}
export function createArchive(storage) {
  let index = { schema: 3, command: 0, days: {} }
  return {
    async load() {
      const raw = await io(storage, 'get', { key: ROOT, default: '' })
      if (raw) {
        const value = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (value.schema !== 3 || !value.days || !Number.isSafeInteger(value.command) || value.command < 0 || Object.keys(value.days).length > 5000) throw new Error('日期索引格式无效')
        Object.keys(value.days).forEach(day => {
          const entry = value.days[day]
          if (!validDay(day) || !Number.isSafeInteger(entry.revision) || entry.revision <= 0 || typeof entry.savedAt !== 'number' || entry.savedAt <= 0 ||
              (entry.key !== LEGACY && entry.key !== PREFIX + day + '.' + entry.revision)) throw new Error('日期索引格式无效')
        })
        index = value
      } else {
        const old = await io(storage, 'get', { key: LEGACY, default: '' })
        if (old) {
          const value = typeof old === 'string' ? JSON.parse(old) : old
          const snapshot = dashboard.validate(value.snapshot)
          if (typeof value.savedAt !== 'number' || value.savedAt <= 0) throw new Error('旧缓存格式无效')
          index.days[snapshot.day] = { revision: snapshot.revision, savedAt: value.savedAt, key: LEGACY }
        }
      }
    },
    dates() { return Object.keys(index.days).sort().reverse() },
    command() { return index.command },
    versions() { const result = {}; Object.keys(index.days).forEach(day => { result[day] = index.days[day].revision }); return result },
    savedAt(day) { return index.days[day] ? index.days[day].savedAt : 0 },
    async read(day) {
      const entry = index.days[day]; if (!entry) return null
      const raw = await io(storage, 'get', { key: entry.key, default: '' })
      if (!raw) throw new Error('该日缓存不可读，请获取更新')
      const value = typeof raw === 'string' ? JSON.parse(raw) : raw
      const snapshot = dashboard.validate(value.snapshot)
      if (snapshot.day !== day || snapshot.revision !== entry.revision) throw new Error('日期缓存版本不一致')
      return snapshot
    },
    async merge(days) {
      const next = { schema: 3, command: index.command, days: { ...index.days } }
      const seen = {}; let changed = 0
      for (const raw of days) {
        const snapshot = dashboard.validate(raw)
        if (seen[snapshot.day]) throw new Error('同一批日期重复'); seen[snapshot.day] = true
        const old = index.days[snapshot.day]
        if (old && snapshot.revision < old.revision) throw new Error('手机日期版本较旧，原缓存已保留')
        if (old && snapshot.revision === old.revision) {
          if (JSON.stringify(await this.read(snapshot.day)) !== JSON.stringify(snapshot)) throw new Error('日期版本冲突，原缓存已保留')
          continue
        }
        const key = PREFIX + snapshot.day + '.' + snapshot.revision; const savedAt = Date.now()
        await io(storage, 'set', { key, value: JSON.stringify({ snapshot, savedAt }) })
        next.days[snapshot.day] = { revision: snapshot.revision, savedAt, key }; changed++
      }
      if (Object.keys(next.days).length > 5000) throw new Error('日期索引过大')
      if (changed) {
        // Commit the index after every new immutable day exists. A failed commit keeps the previous archive.
        await io(storage, 'set', { key: ROOT, value: JSON.stringify(next) })
        const previous = index; index = next
        if (typeof storage.delete === 'function') Object.keys(previous.days).forEach(day => {
          const old = previous.days[day]
          if (old.key !== LEGACY && next.days[day].key !== old.key) { try { storage.delete({ key: old.key, success() {}, fail() {} }) } catch (_) {} }
        })
      }
      return changed
    },
    async markCommand(command) {
      if (command <= index.command) return
      const next = { ...index, command }
      await io(storage, 'set', { key: ROOT, value: JSON.stringify(next) }); index = next
    }
  }
}
