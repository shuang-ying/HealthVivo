function sample(raw, now) {
  if (!raw || typeof raw.value !== 'number' || !isFinite(raw.value) || raw.value <= 0 || raw.value > 300) return null
  if (typeof raw.timeStamp !== 'number' || !isFinite(raw.timeStamp) || raw.timeStamp <= 0 || raw.timeStamp > now + 60000) return null
  return { value: raw.value, timestamp: raw.timeStamp }
}
function stepDelta(previous, current) {
  if (previous === null || previous === undefined || current < previous || current < 0 || !isFinite(current)) return 0
  return Math.max(0, current - previous)
}
function envelope(type, data, now, id) {
  return { type: type, data: Object.assign({}, data, { id: id, source: 'watch', timestamp: now }) }
}
function acknowledge(queue, raw) {
  var message = typeof raw === 'string' ? JSON.parse(raw) : raw
  // Transport success alone never removes pending records.
  if (!message || message.type !== 'fitness.ack.v1' || !message.data || message.data.stored !== true) return queue
  return queue.filter(function (packet) { return packet.data.id !== message.data.id })
}
export default { sample: sample, stepDelta: stepDelta, envelope: envelope, acknowledge: acknowledge }
