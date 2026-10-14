// Persistent connection identity for the watch.
//
// The stored record is the only long-lived secret on the watch: a 32-byte credential plus a
// monotonically increasing epoch and the pending-update flag. Two rules keep it trustworthy:
//
//   * Every mutation is serialised and awaited before the new value reaches the protocol, so the
//     session keys can never be derived from a credential that failed to persist.
//   * The epoch only ever moves forward, so a hello captured from an earlier connection can never
//     be replayed against a later one, even after a restart.
//
// The storage backend is injected, which keeps this module testable without a watch.

import { validSecret } from './session.mjs'

export const IDENTITY_KEY = 'fitness.link.identity.v1'
const MAX_EPOCH = 9007199254740991

/**
 * @param io    { load(): Promise<string>, save(text): Promise<void> }
 * @param clock () => milliseconds
 */
export function createIdentity(io, clock = () => Date.now()) {
  const identity = {
    secret: '', epoch: 0, pending: false, ready: false,
    async load() {
      const raw = await io.load()
      if (raw) {
        const value = typeof raw === 'string' ? JSON.parse(raw) : raw
        if (!value || typeof value !== 'object') throw new Error('连接信息不可读')
        if (value.mode !== undefined && value.mode !== 1) throw new Error('连接信息版本不受支持')
        if (!Number.isSafeInteger(value.epoch) || value.epoch < 0 || typeof value.pending !== 'boolean') throw new Error('连接信息不可读')
        const secret = typeof value.secret === 'string' ? value.secret : ''
        if (secret && !validSecret(secret)) throw new Error('连接信息无效')
        identity.secret = secret; identity.epoch = value.epoch; identity.pending = value.pending
      }
      await queue(() => persist({ secret: identity.secret, epoch: nextEpoch(), pending: identity.pending }))
      identity.ready = true
      return identity
    },
    /** Called after a successful handshake; persists before the caller installs the session. */
    acceptHandshake(secret) { return queue(async () => {
      if (!validSecret(secret)) throw new Error('连接凭据无效')
      await persist({ secret, epoch: nextEpoch(), pending: identity.pending })
    }) },
    /** Called only after the archive committed the final day of a user-requested update. */
    completeRequest() { return queue(() => persist({ secret: identity.secret, epoch: identity.epoch, pending: false })) },
    /** Remember a user-initiated update request so it survives a restart or a flat battery. */
    queueRequest() { return queue(() => persist({ secret: identity.secret, epoch: identity.epoch, pending: true })) },
    /** Forget the phone but keep every cached day. */
    clearSecret() { return queue(() => persist({ secret: '', epoch: nextEpoch(), pending: identity.pending })) },
    /** Forget everything this phone ever sent, including a queued update request. */
    forget() { return queue(() => persist({ secret: '', epoch: nextEpoch(), pending: false })) }
  }

  let tail = Promise.resolve()
  /** Serialise every write so two concurrent handshakes cannot interleave. */
  function queue(work) {
    const next = tail.then(work)
    tail = next.then(() => {}, () => {})
    return next
  }
  function nextEpoch() { return Math.max(clock(), identity.epoch + 1) }
  /** Persist first, then publish: a failed write leaves the in-memory identity untouched. */
  async function persist(value) {
    if (!Number.isSafeInteger(value.epoch) || value.epoch <= 0 || value.epoch > MAX_EPOCH) throw new Error('连接版本无效')
    if (value.secret && !validSecret(value.secret)) throw new Error('连接凭据无效')
    await io.save(JSON.stringify({ mode: 1, secret: value.secret, epoch: value.epoch, pending: value.pending }))
    identity.secret = value.secret; identity.epoch = value.epoch; identity.pending = value.pending
  }
  return identity
}
