const CLIENT = 'com.lrh.healthvivo.watch'
const HISTORY_LIMIT = 500
const MEASUREMENT_LIMIT = 500
const TREND_PLOT = 120
const TREND_WIDTH = 360
const TREND_GAP = 4
const TREND_BAR_MIN = 6
const TREND_BAR_MAX = 40
const TREND_STUB = 3
// How far the top of an axis clears the tallest column, as a fraction of the range. The bounds are
// deliberately not snapped to a grid line: snapping is what forces a maximum of 1973 either to graze
// the frame or to jump a whole step. Only the top moves - a bar measured from anywhere but its own
// floor is no longer proportional to its value.
const AXIS_PAD = 0.08
const TREND_MISSING = '#3A3448'
const TREND_ZERO = '#51485E'
const TREND_NEGATIVE = '#913643'
const TREND_MARK = '#F2C14E'
const TREND_PLOT_MIN_SPAN = 100
// The one sentence a chart page shows when the watch has never received the series, shared so that
// "nothing has ever been synced" reads the same wherever it appears.
const SYNC_PROMPT = '请在手机点击“更新到手表”后查看'
const SERIES_TABS = [
  { key: 'meals', title: '饮食', label: '每日摄入', color: '#7955D8' },
  { key: 'exercise', title: '运动', label: '每日活动', color: '#42AC81' },
  { key: 'balance', title: '热量剩余', label: '每日热量剩余', color: '#19633F' }
]
const MEASURE_TABS = [
  { key: 'weight', title: '体重', unit: ' kg', decimals: 1, color: '#7955D8' },
  { key: 'fat', title: '体脂率', unit: '%', decimals: 1, color: '#42AC81' }
]
const SCALES = [
  { key: 'week', label: '周' },
  { key: 'month', label: '月' },
  { key: 'year', label: '年' }
]
const WEEKDAYS = ['一', '二', '三', '四', '五', '六', '日']
const DATE = /^\d{4}-\d{2}-\d{2}$/
function integer(value) { return typeof value === 'number' && isFinite(value) && Math.floor(value) === value }
function optionalNumber(value) { return value === null || (typeof value === 'number' && isFinite(value) && value >= 0) }
// Absent and JSON null both mean "the phone could not derive this count" (it sends `recordedSteps: null`
// on any day without recorded steps), so a null is accepted exactly like a missing key.
function optionalInteger(value) { return value === undefined || value === null || (integer(value) && value >= 0 && value <= 200000) }
function seriesTab(key) {
  for (let index = 0; index < SERIES_TABS.length; index++) if (SERIES_TABS[index].key === key) return SERIES_TABS[index]
  return SERIES_TABS[0]
}
function measureTab(key) {
  for (let index = 0; index < MEASURE_TABS.length; index++) if (MEASURE_TABS[index].key === key) return MEASURE_TABS[index]
  return MEASURE_TABS[0]
}
// ---------------------------------------------------------------------------
// Calendar arithmetic.
//
// The watch has no java.time, and a column must be the same calendar column the phone draws, so days
// are plain day numbers counted from the civil epoch instead of Date objects: no timezone, no daylight
// saving, and no dependence on how the platform happens to parse a date string. This is the same
// days_from_civil algorithm java.time's LocalDate uses underneath, so the two agree by construction.
// ---------------------------------------------------------------------------
function pad(value, width) { let text = String(value); while (text.length < width) text = '0' + text; return text }
function daysFromCivil(year, month, date) {
  const shift = month <= 2 ? year - 1 : year
  const era = Math.floor(shift / 400)
  const yoe = shift - era * 400
  const doy = Math.floor((153 * (month + (month > 2 ? -3 : 9)) + 2) / 5) + date - 1
  return era * 146097 + yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy - 719468
}
function civilFromDays(value) {
  const shift = value + 719468
  const era = Math.floor(shift / 146097)
  const doe = shift - era * 146097
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365)
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100))
  const mp = Math.floor((5 * doy + 2) / 153)
  const date = doy - Math.floor((153 * mp + 2) / 5) + 1
  const month = mp + (mp < 10 ? 3 : -9)
  return { year: yoe + era * 400 + (month <= 2 ? 1 : 0), month: month, date: date }
}
function dateNumber(day) {
  const parts = day.split('-')
  return daysFromCivil(Number(parts[0]), Number(parts[1]), Number(parts[2]))
}
function dateText(value) {
  const date = civilFromDays(value)
  return pad(date.year, 4) + '-' + pad(date.month, 2) + '-' + pad(date.date, 2)
}
/** ISO weekday: 1 is Monday, 7 is Sunday, the way java.time numbers DayOfWeek. */
function weekday(day) { return ((dateNumber(day) + 3) % 7 + 7) % 7 + 1 }
/** A date the calendar can actually hold: the shape alone would let 2026-02-31 roll into March. */
function validDay(day) { return typeof day === 'string' && DATE.test(day) && dateText(dateNumber(day)) === day }
function monthLength(year, month) {
  return daysFromCivil(month === 12 ? year + 1 : year, month === 12 ? 1 : month + 1, 1) - daysFromCivil(year, month, 1)
}
function shiftDate(day, scale, units) {
  const value = dateNumber(day)
  if (scale === 'week') return dateText(value + units * 7)
  const date = civilFromDays(value)
  if (scale === 'year') {
    const year = date.year + units
    return dateText(daysFromCivil(year, date.month, Math.min(date.date, monthLength(year, date.month))))
  }
  const total = date.year * 12 + (date.month - 1) + units
  const year = Math.floor(total / 12)
  const month = total - year * 12 + 1
  return dateText(daysFromCivil(year, month, Math.min(date.date, monthLength(year, month))))
}
function periodLabel(scale, start, endInclusive) {
  if (scale === 'week') {
    const first = civilFromDays(start)
    const last = civilFromDays(endInclusive)
    return first.month + '/' + first.date + '–' + last.month + '/' + last.date
  }
  const date = civilFromDays(start)
  if (scale === 'month') return date.year + '年' + date.month + '月'
  return date.year + '年'
}
// One week, month or year: always half-open, [start, endExclusive).
function periodFor(day, scale) {
  const value = dateNumber(day)
  let start = value
  let endExclusive = value
  if (scale === 'year') {
    const year = civilFromDays(value).year
    start = daysFromCivil(year, 1, 1)
    endExclusive = daysFromCivil(year + 1, 1, 1)
  } else if (scale === 'month') {
    const date = civilFromDays(value)
    start = daysFromCivil(date.year, date.month, 1)
    endExclusive = start + monthLength(date.year, date.month)
  } else {
    start = value - (weekday(day) - 1)
    endExclusive = start + 7
  }
  return { scale: scale, start: dateText(start), endExclusive: dateText(endExclusive),
    endInclusive: dateText(endExclusive - 1), label: periodLabel(scale, start, endExclusive - 1) }
}
/**
 * The period holding [anchor]. There is no separate "how many periods back" state: the cursor is a
 * date, exactly as it is on the phone, so changing scale keeps the calendar place - standing in the
 * week of 10/05 and choosing 月 opens October 2026.
 */
function period(anchor, scale) {
  return periodFor(anchor, scale)
}
/** The columns of a period, oldest first: a day for a week or a month, a whole month for a year. */
function columns(model) {
  const result = []
  const start = dateNumber(model.start)
  const endExclusive = dateNumber(model.endExclusive)
  if (model.scale === 'year') {
    const year = civilFromDays(start).year
    for (let month = 1; month <= 12; month++) {
      const first = daysFromCivil(year, month, 1)
      const last = Math.min(first + monthLength(year, month) - 1, endExclusive - 1)
      result.push([first, last])
    }
    return result
  }
  for (let day = start; day < endExclusive; day++) result.push([day, day])
  return result
}
function columnLabel(scale, start) {
  if (scale === 'week') return WEEKDAYS[weekday(dateText(start)) - 1]
  const date = civilFromDays(start)
  if (scale === 'month') return String(date.date)
  return date.month + '月'
}
function columnShort(scale, start) {
  const date = civilFromDays(start)
  if (scale === 'year') return date.month + '月'
  return date.month + '/' + date.date
}
function columnText(scale, start) {
  const date = civilFromDays(start)
  if (scale === 'week') return date.month + '/' + date.date + ' 周' + WEEKDAYS[weekday(dateText(start)) - 1]
  if (scale === 'month') return date.month + '/' + date.date
  return date.year + '年' + date.month + '月'
}
function known(value) { return value !== null && value !== undefined ? value : 0 }
/**
 * The intake, recorded activity and remaining reference of every column of [period].
 *
 * The watch only adds up the sparse history the phone sent: every energy rule - which weight is in
 * force, which days count, what a day's reference is - is applied on the phone, so no rule exists
 * twice and the two cannot drift apart. A column with no record keeps zeros rather than being
 * dropped, which preserves the calendar spacing; `recorded` and `future` keep that from being read
 * as a measured zero.
 */
function energy(days, model, today) {
  const history = Array.isArray(days) ? days : []
  const limit = DATE.test(today || '') ? dateNumber(today) : null
  return columns(model).map(column => {
    let intake = 0; let activity = 0; let reference = 0; let referenceKnown = false; let recorded = false
    history.forEach(entry => {
      if (!entry || typeof entry.day !== 'string' || !DATE.test(entry.day)) return
      const value = dateNumber(entry.day)
      if (value < column[0] || value > column[1]) return
      recorded = true
      intake += known(entry.intakeKcal)
      activity += known(entry.activityKcal)
      if (entry.referenceKcal !== null && entry.referenceKcal !== undefined) { reference += entry.referenceKcal; referenceKnown = true }
    })
    return { startDay: dateText(column[0]), label: columnLabel(model.scale, column[0]), shortText: columnShort(model.scale, column[0]),
      rangeText: columnText(model.scale, column[0]), intakeKcal: intake, activityKcal: activity,
      referenceKcal: referenceKnown ? reference : null, recorded: recorded,
      future: limit !== null && column[0] > limit }
  })
}
/**
 * A sparse measurement per column: the reading of that day, or - for a year view - the last reading
 * inside that month, which is the state the month ended in. Gaps stay gaps rather than becoming
 * zeros, because "not measured" is not "measured as nothing".
 */
function measure(values, model, today) {
  const history = Array.isArray(values) ? values : []
  const limit = DATE.test(today || '') ? dateNumber(today) : null
  return columns(model).map(column => {
    let value = null; let found = null
    history.forEach(entry => {
      if (!entry || typeof entry.day !== 'string' || !DATE.test(entry.day)) return
      const day = dateNumber(entry.day)
      if (day < column[0] || day > column[1]) return
      if (found === null || day >= found) { found = day; value = entry.value }
    })
    return { startDay: dateText(column[0]), label: columnLabel(model.scale, column[0]), shortText: columnShort(model.scale, column[0]),
      rangeText: columnText(model.scale, column[0]), value: value, future: limit !== null && column[0] > limit }
  })
}
/**
 * The app-wide sparse series: the days that hold an energy record, plus the weight and body-fat
 * measurements. The phone attaches it to the one snapshot whose `day` is its own today and sends an
 * explicit null with every other day, so a history read back from the watch's own storage has no
 * snapshot to be bounded by - `today` is then omitted and only the shape, the order and the caps are
 * checked. Everything the phone can derive a figure for was already bounded when it was received.
 */
function validateHistory(history, today) {
  if (!history || typeof history !== 'object' || Array.isArray(history)) throw new Error('history')
  if (!Array.isArray(history.days) || history.days.length > HISTORY_LIMIT ||
      !Array.isArray(history.weights) || history.weights.length > MEASUREMENT_LIMIT ||
      !Array.isArray(history.fats) || history.fats.length > MEASUREMENT_LIMIT) throw new Error('history')
  const limit = DATE.test(today || '') ? today : null
  let previous = ''
  history.days.forEach(entry => {
    // The phone sends only days that hold a record, oldest first and never past its own today; a day
    // after today would be a date the phone cannot have derived an energy figure for.
    if (!entry || typeof entry.day !== 'string' || !DATE.test(entry.day) || entry.day <= previous ||
        (limit !== null && entry.day > limit) ||
        !optionalNumber(entry.intakeKcal) || entry.intakeKcal === null ||
        !optionalNumber(entry.activityKcal) || entry.activityKcal === null || !optionalNumber(entry.referenceKcal)) throw new Error('history day')
    previous = entry.day
  })
  // Measurements are a plain sparse series: a reading may be dated after today (the phone keeps the
  // whole series, not just the days up to the one being viewed), but it must still be one per day and
  // in order, or the "last reading of the month" would depend on the order of the file.
  ;['weights', 'fats'].forEach(key => {
    let seen = ''
    history[key].forEach(entry => {
      if (!entry || typeof entry.day !== 'string' || !DATE.test(entry.day) || entry.day <= seen ||
          !optionalNumber(entry.value) || entry.value === null) throw new Error('history ' + key)
      seen = entry.day
    })
  })
}
function validateScale(scale, value) {
  if (scale === undefined || scale === null) return
  if (!optionalNumber(scale.min) || scale.min === null || !optionalNumber(scale.max) || scale.max === null || scale.max <= scale.min ||
      !Array.isArray(scale.breaks) || scale.breaks.length < 1 || scale.breaks.length > 3 || !Array.isArray(scale.labels) || !Array.isArray(scale.colors) ||
      scale.labels.length !== scale.breaks.length + 1 || scale.colors.length !== scale.labels.length || scale.value !== value ||
      !optionalNumber(scale.value) || ['', '%'].indexOf(scale.unit) < 0) throw new Error('body scale')
  const points = [scale.min].concat(scale.breaks, [scale.max])
  points.forEach((point, index) => { if (typeof point !== 'number' || !isFinite(point) || (index && point <= points[index - 1])) throw new Error('body scale boundary') })
  scale.labels.forEach(label => { if (typeof label !== 'string' || label.length > 10) throw new Error('body scale label') })
  scale.colors.forEach(color => { if (typeof color !== 'string' || !/^#[0-9A-Fa-f]{6}$/.test(color)) throw new Error('body scale color') })
}
function scaleLayout(scale) {
  if (!scale) return null
  const width = 300; const points = [scale.min].concat(scale.breaks, [scale.max])
  const x = value => Math.max(0, Math.min(1, (value - scale.min) / (scale.max - scale.min))) * width
  return { hasMarker: scale.value !== null, markerLeft: x(scale.value === null ? scale.min : scale.value) - 10,
    bands: scale.colors.map((color, index) => ({ width: x(points[index + 1]) - x(points[index]), color })),
    ticks: points.map((point, index) => ({ text: (index === 0 ? '≤' : index === points.length - 1 ? '≥' : '') + point + scale.unit, left: Math.max(0, Math.min(width - 44, x(point) - 22)) })),
    labels: scale.labels.map((text, index) => ({ text, left: x((points[index] + points[index + 1]) / 2) - 30 })) }
}
function checksum(text) {
  let hash = 5381
  for (let i = 0; i < text.length; i++) hash = (hash * 31 + text.charCodeAt(i)) >>> 0
  return hash.toString(16)
}
function validate(value) {
  if (!value || value.schema !== 3 || value.client !== CLIENT || !DATE.test(value.day) || !DATE.test(value.today) ||
      !integer(value.revision) || value.revision <= 0 || !integer(value.generatedAt) || value.generatedAt <= 0 ||
      !(value.steps === null || (integer(value.steps) && value.steps >= 0 && value.steps <= 200000)) ||
      !optionalNumber(value.weightKg) || !optionalNumber(value.targetWeightKg)) throw new Error('snapshot')
  if (!optionalInteger(value.workoutSteps) || !optionalInteger(value.recordedSteps) ||
      (value.recordedSteps !== undefined && value.recordedSteps !== null && value.steps !== null && value.recordedSteps > value.steps)) throw new Error('steps')
  const energy = value.energy
  if (value.body !== undefined) {
    const body = value.body
    if (!body || !optionalNumber(body.bmi) || !optionalNumber(body.fatPercent) ||
        (body.fatPercent !== null && (body.fatPercent <= 0 || body.fatPercent >= 100)) ||
        !(body.fatDay === null || (typeof body.fatDay === 'string' && DATE.test(body.fatDay))) ||
        typeof body.bmiStatus !== 'string' || body.bmiStatus.length > 40 || typeof body.fatStatus !== 'string' || body.fatStatus.length > 40) throw new Error('body')
    validateScale(body.bmiScale, body.bmi); validateScale(body.fatScale, body.fatPercent)
  }
  if (!energy || !optionalNumber(energy.intakeKcal) || !optionalNumber(energy.restingKcal) ||
      !optionalNumber(energy.activityKcal) || !optionalNumber(energy.referenceKcal) ||
      !integer(energy.pendingCount) || energy.pendingCount < 0 || typeof energy.overlapHandled !== 'boolean') throw new Error('energy')
  if ((energy.stepsKcal !== undefined && !optionalNumber(energy.stepsKcal)) ||
      (energy.workoutKcal !== undefined && !optionalNumber(energy.workoutKcal)) ||
      (energy.mealKcal !== undefined && (!Array.isArray(energy.mealKcal) || energy.mealKcal.length !== 4))) throw new Error('energy')
  if (energy.mealKcal !== undefined) energy.mealKcal.forEach(meal => {
    if (typeof meal !== 'number' || !isFinite(meal) || meal < 0) throw new Error('energy meal')
  })
  // The series belongs to the whole app rather than to a day: the phone attaches it to the snapshot
  // whose day is its own today and sends an explicit null with every other day. A key that is missing
  // altogether is still refused - the phone's own contract always writes one of the two.
  if (value.history !== null) validateHistory(value.history, value.today)
  if (!Array.isArray(value.meals) || value.meals.length !== 4 || !Array.isArray(value.exercises) || value.exercises.length > 20 ||
      !integer(value.exerciseCount) || value.exerciseCount < 0 || !integer(value.omittedExercises) || value.omittedExercises < 0 ||
      value.exerciseCount !== value.exercises.length + value.omittedExercises) throw new Error('records')
  const kinds = ['breakfast', 'lunch', 'dinner', 'snack']
  value.meals.forEach((meal, index) => {
    if (!meal || meal.kind !== kinds[index] || !integer(meal.count) || meal.count < 0 || !integer(meal.omitted) || meal.omitted < 0 ||
        !optionalNumber(meal.kcal) || !Array.isArray(meal.foods) || meal.foods.length > 20 ||
        meal.count !== meal.foods.length + meal.omitted) throw new Error('meal')
    meal.foods.forEach(food => {
      if (!food || typeof food.name !== 'string' || food.name.length > 120 || !optionalNumber(food.grams) ||
          food.grams === null || !optionalNumber(food.kcal) || food.kcal === null || typeof food.estimated !== 'boolean') throw new Error('food')
    })
  })
  value.exercises.forEach(item => {
    if (!item || typeof item.name !== 'string' || item.name.length > 120 ||
        ['aerobic', 'anaerobic', 'unspecified'].indexOf(item.category) < 0 ||
        !optionalNumber(item.minutes) || !optionalNumber(item.distanceKm) || !optionalNumber(item.sets) ||
        !optionalNumber(item.reps) || !optionalNumber(item.loadKg) || !optionalNumber(item.kcal) ||
        !optionalInteger(item.steps) ||
        typeof item.net !== 'boolean' || typeof item.estimated !== 'boolean' || typeof item.includedInSteps !== 'boolean') throw new Error('exercise')
  })
  if (value.advice !== null) {
    const advice = value.advice
    if (!advice || !integer(advice.generatedAt) || advice.generatedAt <= 0 || typeof advice.stale !== 'boolean' || !advice.content) throw new Error('advice')
    ;['food', 'exercise'].forEach(key => {
      if (typeof advice.content[key] !== 'string' || advice.content[key].length > 1600) throw new Error('advice text')
    })

  }
  return value
}
function assembler() {
  let active = null
  return {
    clear() { active = null },
    accept(raw, committedRevision, now) {
      if (typeof raw === 'string' && raw.length > 4096) return null
      const frame = typeof raw === 'string' ? JSON.parse(raw) : raw
      if (!frame || frame.type !== 'fitness.dashboard.frame.v2' || frame.client !== CLIENT) return null
      if (typeof frame.id !== 'string' || frame.id.length > 80 || !integer(frame.revision) || frame.revision <= committedRevision ||
          typeof frame.checksum !== 'string' || !/^[0-9a-f]{1,8}$/.test(frame.checksum) || !integer(frame.total) ||
          frame.total < 1 || frame.total > 256 || !integer(frame.index) || frame.index < 0 || frame.index >= frame.total ||
          typeof frame.text !== 'string' || frame.text.length > 512) return null
      if (active && now - active.startedAt > 25000) active = null
      if (active && frame.revision < active.revision) return null
      if (!active || frame.revision > active.revision) active = {
        id: frame.id, revision: frame.revision, checksum: frame.checksum, total: frame.total, parts: {}, count: 0, startedAt: now
      }
      if (frame.id !== active.id || frame.total !== active.total || frame.checksum !== active.checksum) throw new Error('conflicting frame')
      if (Object.prototype.hasOwnProperty.call(active.parts, frame.index)) {
        if (active.parts[frame.index] !== frame.text) throw new Error('conflicting fragment')
      } else { active.parts[frame.index] = frame.text; active.count++ }
      if (active.count < active.total) return { pending: true, count: active.count, total: active.total }
      let text = ''
      for (let i = 0; i < active.total; i++) text += active.parts[i]
      const transfer = active; active = null
      if (text.length > 128 * 1024 || checksum(text) !== transfer.checksum) throw new Error('checksum')
      const snapshot = validate(JSON.parse(text))
      if (snapshot.revision !== transfer.revision) throw new Error('revision')
      return { snapshot: snapshot, id: transfer.id, checksum: transfer.checksum, revision: transfer.revision }
    }
  }
}
function number(value, unit) { return value === null || value === undefined ? '未记录' : Math.round(value * 10) / 10 + (unit || '') }
function kcal(value) { return value === null || value === undefined ? '待补全' : Math.round(value) + ' 千卡' }
function signedKcal(value) { return value === null || value === undefined ? '待补全' : (value >= 0 ? '+' : '−') + Math.round(Math.abs(value)) + ' 千卡' }
function time(value) {
  const date = new Date(value)
  return (date.getMonth() + 1) + '/' + date.getDate() + ' ' + date.getHours() + ':' + ('0' + date.getMinutes()).slice(-2)
}
// ---------------------------------------------------------------------------
// Page names.
//
// The chart state travels in the page name: which series, which scale, which day the cursor stands on
// and which column of that period was tapped. The cursor is a date rather than a count of periods, so
// changing scale keeps the calendar place the way it does on the phone, and the anchor is re-derived
// against the snapshot's own day every time the page is built.
// ---------------------------------------------------------------------------
function pageName(kind, series, scale, anchor, index) {
  let name = kind + ':' + series + ':' + scale + ':' + anchor
  if (index !== undefined && index !== null) name += ':' + index
  return name
}
function parse(page) {
  if (typeof page !== 'string') return null
  let kind = ''
  if (page.indexOf('trend:') === 0) kind = 'trend'
  else if (page.indexOf('measure:') === 0) kind = 'measure'
  else return null
  const parts = page.split(':')
  if (parts.length > 5) return null
  const series = parts[1]
  const serieses = kind === 'trend' ? ['meals', 'exercise', 'balance'] : ['weight', 'fat']
  if (serieses.indexOf(series) < 0) return null
  let scale = 'week'; let anchor = null; let index = null
  // `trend:meals` predates the scales and still resolves to the present week, and a page may leave the
  // anchor out altogether: either way the page follows the snapshot's own day.
  if (parts.length > 2) { scale = parts[2]; if (['week', 'month', 'year'].indexOf(scale) < 0) return null }
  if (parts.length > 3) { if (!validDay(parts[3])) return null; anchor = parts[3] }
  if (parts.length > 4) { if (!/^\d{1,3}$/.test(parts[4])) return null; index = Number(parts[4]); if (index > 400) return null }
  return { kind: kind, series: series, scale: scale, anchor: anchor, index: index }
}
/**
 * The page state resolved against the snapshot: which period is on screen, whether it is the present
 * one, and where a step may go.
 *
 * The cursor may name any day up to the snapshot's own - a later one would show a period that has not
 * happened - so it is clamped rather than refused. Stepping moves it one unit of the current scale
 * (Trends.shift), and the forward step is never offered past the period holding the snapshot's day.
 */
function pageState(snapshot, page) {
  const state = parse(page)
  if (!state) return null
  if (!state.anchor || state.anchor > snapshot.day) state.anchor = snapshot.day
  state.model = period(state.anchor, state.scale)
  const current = period(snapshot.day, state.scale)
  state.atCurrent = state.model.start === current.start
  state.canForward = state.model.start < current.start
  const forward = shiftDate(state.anchor, state.scale, 1)
  state.nextAnchor = forward > snapshot.day ? snapshot.day : forward
  return state
}
function title(page) {
  const state = parse(page)
  if (!state) return ''
  if (state.kind === 'measure') return state.series === 'fat' ? '体脂率趋势' : '体重趋势'
  return seriesTab(state.series).title + '趋势'
}
/**
 * Whether [next] stays inside the same card as [page].
 *
 * A series tab, a scale chip, a period step and a column tap all redraw the chart page the user is
 * already standing on, so the list must keep the scroll offset it has - only leaving the card (or
 * opening one from the dashboard) is a move worth starting at the top for.
 */
function sameChart(page, next) {
  const before = parse(page); const after = parse(next)
  return !!before && !!after && before.kind === after.kind
}
/** The three series of the app-wide history, or null when the watch has never received one. */
function historySeries(history) {
  if (!history || typeof history !== 'object') return null
  if (!Array.isArray(history.days) || !Array.isArray(history.weights) || !Array.isArray(history.fats)) return null
  return history
}
/** The newest day a sparse series actually holds, or '' when it holds nothing. */
function newestDay(entries) {
  let newest = ''
  if (Array.isArray(entries)) entries.forEach(entry => {
    if (entry && typeof entry.day === 'string' && entry.day > newest) newest = entry.day
  })
  return newest
}
/**
 * The day the columns are measured against: the later of the snapshot's own `today` and the newest
 * day the series being drawn holds.
 *
 * The phone writes a day's snapshot when that day's *content* changes, not at every midnight, so a
 * cached day can carry a `today` older than the history the watch now keeps. Measuring against that
 * stale bound would draw the newest reading - the 14th's weight, seen while the 12th is on screen - as
 * "not yet happened". The last entry of the series is a floor for the phone's today, so the later of
 * the two is used; a column with no record either way is still a gap rather than a measured zero.
 */
function historyToday(snapshot, entries) {
  const newest = newestDay(entries)
  return newest > snapshot.today ? newest : snapshot.today
}
function seriesValue(series, bucket) {
  if (series === 'exercise') return bucket.activityKcal
  if (series === 'balance') return bucket.referenceKcal === null ? null : bucket.referenceKcal - bucket.intakeKcal
  return bucket.intakeKcal
}
function trendAmount(series, value) {
  if (value === null || value === undefined) return '待补全'
  if (series !== 'balance') return kcal(value)
  return signedKcal(value)
}
function barWidth(count) { return Math.max(TREND_BAR_MIN, Math.min(TREND_BAR_MAX, Math.floor(TREND_WIDTH / count) - TREND_GAP)) }
function markerLeft(count, width, index) {
  const first = (TREND_WIDTH - count * (width + TREND_GAP)) / 2
  return Math.round(first + index * (width + TREND_GAP) + width / 2 - 10)
}
function labelStride(scale) { return scale === 'week' ? 1 : scale === 'month' ? 5 : 2 }
function decimalStep(decimals) { let value = 1; for (let index = 0; index < decimals; index++) value = value * 10; return value }
function floorTo(value, decimals) { const step = decimalStep(decimals); return Math.floor(value * step) / step }
function ceilTo(value, decimals) { const step = decimalStep(decimals); return Math.ceil(value * step) / step }
// ---------------------------------------------------------------------------
// The plot.
//
// Every bar carries both its offset from the top of the plot and its own length, so a bar can hang
// below the zero line as easily as it rises above it. A chart whose floor is zero is simply
// top = plotHeight - height, which is what a bottom-anchored row used to do implicitly.
// ---------------------------------------------------------------------------
/** Where a value sits on the axis, in pixels from the top of the plot. */
function plotY(value, axis) { return TREND_PLOT * (1 - (value - axis.low) / (axis.top - axis.low)) }
/**
 * A bar whose floor is the axis floor: length alone decides where it starts. A chart whose axis floor
 * is zero therefore gets length proportional to the value, which is what makes it a bar chart.
 */
function floorBox(value, axis) {
  const height = Math.max(TREND_STUB, Math.round((value - axis.low) / (axis.top - axis.low) * TREND_PLOT))
  return { top: TREND_PLOT - height, height: height }
}
/**
 * The energy axis, mirroring Trends.kt's axisFor(.., minSpan = 100, keepLow = true).
 *
 * The floor is the data's own low - a bar measured from anywhere else stops being proportional to its
 * value - and the top clears the data by 8% of the range rather than being snapped to a grid line. The
 * balance series is signed: its floor is the deepest deficit and its top the largest surplus, so the
 * zero line sits inside the plot and a deficit hangs below it.
 */
function energyAxis(series, values) {
  let low = 0; let high = 0
  values.forEach(value => {
    if (value === null) return
    if (series === 'balance') { if (value < low) low = value; if (value > high) high = value }
    else if (Math.abs(value) > high) high = Math.abs(value)
  })
  let span = high - low
  if (!(span > 1e-9)) span = TREND_PLOT_MIN_SPAN
  return { low: low, top: high + span * AXIS_PAD }
}
/** A signed bar, measured from the zero line: a surplus rises from it, a deficit hangs below it. */
function balanceBox(value, axis, zero) {
  const point = Math.round(plotY(value, axis))
  if (value >= 0) { const height = Math.max(TREND_STUB, zero - point); return { top: zero - height, height: height } }
  return { top: zero, height: Math.max(TREND_STUB, point - zero) }
}
/** The last column that actually holds something: a record for the burn charts, a reading for the rest. */
function lastFilled(columns, filled) {
  for (let index = columns.length - 1; index >= 0; index--) if (filled(columns[index])) return index
  return null
}
function clamped(index, count) {
  if (index === null || index === undefined || index < 0) return null
  return Math.min(index, count - 1)
}
function choiceRow(row, options, active, build) {
  const item = row('时间范围', '', '')
  item.choices = true; item.hint = ''
  const slots = ['left', 'middle', 'right']
  options.forEach((option, index) => {
    item[slots[index]] = { text: option.label, action: build(option.key), cardClass: option.key === active ? 'choice choice-on' : 'choice' }
  })
  return item
}
function periodRows(row, model, previous, next, reset) {
  const item = row('时段', '', '')
  item.periodText = model.label; item.previous = previous; item.next = next || ''
  item.canNext = !!next; item.reset = reset; item.hint = ''
  return item
}
function scaleName(scale) { return scale === 'week' ? '周' : scale === 'month' ? '月' : '年' }
/**
 * The way back to the present period, which exists on every chart page and in every period.
 *
 * It is deliberately never omitted: a row that appeared only once the user had stepped away from the
 * present would be an insertion into the list, and the list resets its scroll offset when its items
 * appear or disappear (ScrollAdapterList "forceLayout: reset deleting/inserting"). At the present it
 * is the state line instead of a button - the period value in the row above resets too.
 */
function resetRow(row, reset, atCurrent, scale) {
  return atCurrent ? row('当前时段', '已在最近的' + scaleName(scale), '')
    : row('回到当前时段', '点这里回到最近的' + scaleName(scale), reset)
}
/**
 * What the readout says for one column: the figure when the column holds a record, "not recorded" for
 * a gap inside the period, "not yet" for a column past the last day the phone has reached. A gap must
 * never read as a measured zero, which is what the raw column value would print.
 */
function trendReadout(series, bucket, value) {
  if (!bucket.recorded) return bucket.future ? '还没到这段日期' : '未记录'
  return trendAmount(series, value)
}
/**
 * The rows of one dashboard page.
 *
 * [history] is the app-wide series the archive keeps (the `history` of the newest snapshot the phone
 * sent, or null when none has ever arrived). It is an input rather than something read out of
 * [snapshot], because the series does not belong to the day on screen: viewing the 12th must still
 * draw the 14th's reading, which only the newest day's snapshot carried.
 */
function rows(snapshot, page, history) {
  const result = []
  function row(title, body, action, highlight) { const item = { id: String(result.length), title, body, action: action || '', highlight: !!highlight, cardClass: 'card', hint: action ? '点击查看 ›' : '' }; result.push(item); return item }
  function paragraphs(title, text) {
    // Split by characters rather than by code units so a surrogate pair is never cut in half. No
    // Array.from: the watch engine does not guarantee it, and the advance is easy to write by hand.
    const value = String(text)
    let start = 0; let index = 0
    while (start < value.length) {
      let end = Math.min(start + 90, value.length)
      if (end < value.length && value.charCodeAt(end - 1) >= 0xD800 && value.charCodeAt(end - 1) <= 0xDBFF) end--
      row(index ? title + ' · 续' : title, value.slice(start, end))
      start = end; index++
    }
  }
  if (!snapshot) { row('这一天暂无已同步记录', '可在手机录入后点击更新，或在此点击获取更新。'); return result }
  const state = pageState(snapshot, page)
  function energyCard(action) {
    const item = row('每日能量参考', '消耗 ' + kcal(snapshot.energy.referenceKcal) + '\n登记摄入 ' + kcal(snapshot.energy.intakeKcal), action)
    const balance = snapshot.energy.referenceKcal === null || snapshot.energy.intakeKcal === null ? null : snapshot.energy.referenceKcal - snapshot.energy.intakeKcal
    item.balance = balance === null ? '待补充记录' : (balance >= 0 ? '+' : '−') + number(Math.abs(balance)) + ' kcal'
    item.balanceColor = balance === null ? '#51485E' : balance >= 0 ? '#19633F' : '#913643'
  }
  function stepsRow() {
    if (snapshot.steps === null || !snapshot.workoutSteps) { row('每日步数', number(snapshot.steps, ' 步'), '', true); return }
    const missing = snapshot.recordedSteps === undefined || snapshot.recordedSteps === null
    const recorded = missing ? Math.max(0, snapshot.steps - snapshot.workoutSteps) : snapshot.recordedSteps
    row('每日步数', number(snapshot.steps, ' 步') + '\n运动 ' + number(snapshot.workoutSteps) + ' + 记录 ' + number(recorded), '', true)
  }
  /**
   * The chart itself: one bar per column, proportional inside the axis, with the marked column named.
   *
   * Every column is a tap target of its own - the bar carries the page name that selects it - because
   * the dated list that used to sit under the chart read as a second date switcher and is gone.
   */
  function trendChart(series, buckets, values, selected, model, named) {
    const tab = seriesTab(series)
    const width = barWidth(buckets.length)
    const axis = energyAxis(series, values)
    // The zero line is where a signed bar is measured from, and where a pale stub sits.
    const zero = Math.round(plotY(0, axis))
    let sum = 0; let counted = 0
    values.forEach(value => { if (value !== null) { sum += value; counted++ } })
    const chart = row(tab.label, counted ? '合计 ' + trendAmount(series, sum) : '尚无记录', '', true)
    const stride = labelStride(model.scale)
    chart.bars = buckets.map((bucket, index) => {
      const value = values[index]
      const marked = index === selected
      const filled = bucket.recorded && !bucket.future && value !== null
      let box = { top: Math.max(0, zero - TREND_STUB), height: TREND_STUB }; let color = TREND_MISSING
      if (filled && !value) { color = TREND_ZERO }
      else if (filled) {
        box = series === 'balance' ? balanceBox(value, axis, zero) : floorBox(value, axis)
        color = marked ? TREND_MARK : series === 'balance' && value < 0 ? TREND_NEGATIVE : tab.color
      }
      return { width: width, top: box.top, height: box.height, color: color,
        label: index % stride === 0 || marked ? bucket.label : '',
        labelClass: marked ? 'trend-label trend-label-on' : 'trend-label',
        action: named(index) }
    })
    chart.marker = selected !== null
    chart.markerLeft = selected === null ? 0 : markerLeft(buckets.length, width, selected)
    chart.axisTop = '轴顶 ' + number(axis.top) + ' 千卡'
    chart.axisBottom = '轴底 ' + number(axis.low) + ' 千卡'
  }
  /**
   * The energy chart page.
   *
   * The row set is fixed and identical in every series, every scale and every period: three tabs, the
   * scale chips, the period, the way back, the chart, the readout and the day's breakdown, in that
   * order. Only what is *inside* a row varies with the data. Nothing here may emit a row conditionally,
   * because a row that appears or disappears is an insertion or a deletion in the list, and the list
   * resets its scroll offset whenever its items change - which is what threw the user back to the top
   * on every series and scale switch. The columns themselves are a repeat inside the chart's own div,
   * so 7 -> 31 -> 12 bars never touches the row set.
   */
  function trendPage() {
    const named = index => pageName('trend', state.series, state.scale, state.anchor, index)
    const tab = seriesTab(state.series)
    SERIES_TABS.forEach(item => {
      const strip = row(item.title, item.key === state.series ? '当前显示' : '点击切换', pageName('trend', item.key, state.scale, state.anchor))
      strip.tab = true; strip.active = item.key === state.series; strip.cardClass = item.key === state.series ? 'tab-on' : 'tab-off'; strip.hint = ''
    })
    choiceRow(row, SCALES, state.scale, key => pageName('trend', state.series, key, state.anchor))
    periodRows(row, state.model, pageName('trend', state.series, state.scale, shiftDate(state.anchor, state.scale, -1)),
      state.canForward ? pageName('trend', state.series, state.scale, state.nextAnchor) : '',
      pageName('trend', state.series, state.scale, snapshot.day))
    resetRow(row, pageName('trend', state.series, state.scale, snapshot.day), state.atCurrent, state.scale)
    const series = historySeries(history)
    if (!series) {
      // No series has ever arrived, but the slots that will hold it are already here, so the sync that
      // eventually delivers it changes their content rather than inserting rows.
      row(tab.label, '尚无记录', '', true)
      row('趋势数据待同步', SYNC_PROMPT)
      row('当日明细', '—', '', true)
      return
    }
    const buckets = energy(series.days, state.model, historyToday(snapshot, series.days))
    const values = buckets.map(bucket => seriesValue(state.series, bucket))
    const tapped = clamped(state.index, buckets.length)
    const shown = tapped === null ? lastFilled(buckets, bucket => bucket.recorded) : tapped
    const bucket = shown === null ? null : buckets[shown]
    trendChart(state.series, buckets, values, shown, state.model, named)
    if (bucket === null) row('这一段没有记录', '可在手机录入后点击更新，或在此点击获取更新。')
    else row(bucket.rangeText, trendReadout(state.series, bucket, values[shown]), '', true)
    row('当日明细', bucket === null ? '—' : '摄入 ' + kcal(bucket.intakeKcal) + ' · 运动 ' + kcal(bucket.activityKcal) +
      ' · 剩余 ' + trendAmount('balance', bucket.referenceKcal === null ? null : bucket.referenceKcal - bucket.intakeKcal))
  }
  /**
   * The weight and body-fat chart page. Same rule as the energy page: the same rows in the same order
   * in every series, scale and period, with the numbers moving inside them. A period with no reading
   * at all still fills every slot - the chart slot simply arrives without its plot.
   */
  function measurePage() {
    const tab = measureTab(state.series)
    const named = index => pageName('measure', state.series, state.scale, state.anchor, index)
    MEASURE_TABS.forEach(item => {
      const strip = row(item.title, item.key === state.series ? '当前显示' : '点击切换', pageName('measure', item.key, state.scale, state.anchor))
      strip.tab = true; strip.active = item.key === state.series; strip.cardClass = item.key === state.series ? 'tab-on' : 'tab-off'; strip.hint = ''
    })
    choiceRow(row, SCALES, state.scale, key => pageName('measure', state.series, key, state.anchor))
    periodRows(row, state.model, pageName('measure', state.series, state.scale, shiftDate(state.anchor, state.scale, -1)),
      state.canForward ? pageName('measure', state.series, state.scale, state.nextAnchor) : '',
      pageName('measure', state.series, state.scale, snapshot.day))
    resetRow(row, pageName('measure', state.series, state.scale, snapshot.day), state.atCurrent, state.scale)
    const series = historySeries(history)
    if (!series) {
      row(tab.title, '这一段没有记录', '', true)
      row('这一段变化', '—')
      row('趋势数据待同步', SYNC_PROMPT)
      return
    }
    const source = state.series === 'fat' ? series.fats : series.weights
    const points = measure(source, state.model, historyToday(snapshot, source))
    const present = []
    points.forEach(point => { if (point.value !== null) present.push(point.value) })
    const chart = row(tab.title, present.length ? '这一段有 ' + present.length + ' 次记录' : '这一段没有记录', '', true)
    const tapped = clamped(state.index, points.length)
    const shown = tapped === null ? lastFilled(points, point => point.value !== null) : tapped
    if (present.length) {
      let lowest = present[0]; let highest = present[0]
      present.forEach(value => { if (value < lowest) lowest = value; if (value > highest) highest = value })
      let span = highest - lowest
      if (!(span > 1e-9)) span = 1
      // A measurement axis must not start at 0, or a two-kilogram change would vanish into the margin.
      // Both ends clear the readings by the same fraction, so the lowest point is not drawn on the frame
      // either; rounding outward keeps that true after the text has been rounded too.
      const axis = { low: floorTo(lowest - span * AXIS_PAD, tab.decimals), top: ceilTo(highest + span * AXIS_PAD, tab.decimals) }
      const width = barWidth(points.length)
      const stride = labelStride(state.scale)
      chart.bars = points.map((point, index) => {
        const marked = index === shown
        const filled = point.value !== null && !point.future
        const box = filled ? floorBox(point.value, axis) : { top: TREND_PLOT - TREND_STUB, height: TREND_STUB }
        return { width: width, top: box.top, height: box.height, color: filled ? (marked ? TREND_MARK : tab.color) : TREND_MISSING,
          label: index % stride === 0 || marked ? point.label : '',
          labelClass: marked ? 'trend-label trend-label-on' : 'trend-label',
          action: named(index) }
      })
      chart.marker = shown !== null
      chart.markerLeft = shown === null ? 0 : markerLeft(points.length, width, shown)
      chart.axisTop = '轴顶 ' + number(axis.top) + tab.unit
      chart.axisBottom = '轴底 ' + number(axis.low) + tab.unit
    }
    const change = present.length ? present[present.length - 1] - present[0] : null
    row('这一段变化', change === null ? '—'
      : (change > 0 ? '+' : change < 0 ? '−' : '') + number(Math.abs(change)) + tab.unit + ' · ' + present.length + ' 次记录')
    if (shown === null) row('尚未记录' + tab.title, '在手机记录后获取更新')
    else {
      const point = points[shown]
      row(point.rangeText, point.value === null ? (point.future ? '还没到这段日期' : '未记录') : number(point.value, tab.unit), '', true)
    }
  }
  if (page === 'home') {
    stepsRow()
    energyCard('energy')
    row('趋势', '饮食 · 运动 · 热量剩余', pageName('trend', 'meals', 'week', snapshot.day))
    row('体重与体脂趋势', '体重 · 体脂率', pageName('measure', 'weight', 'week', snapshot.day))
    row('运动记录', snapshot.exerciseCount + ' 项 · 已知净活动 ' + kcal(snapshot.energy.activityKcal), 'exercise')
    row('三餐', snapshot.meals.slice(0, 3).map(meal => ({ breakfast: '早', lunch: '中', dinner: '晚' })[meal.kind] + ' ' + meal.count + ' 项').join(' · '), 'meals')
    row('AI 建议', !snapshot.advice ? '尚未生成' : '查看吃饭与锻炼建议', 'advice')
    row('体重与目标', '当前 ' + number(snapshot.weightKg, ' kg') + '\n目标 ' + number(snapshot.targetWeightKg, ' kg'))
    if (snapshot.body) {
      const bmi = row('BMI · ' + snapshot.body.bmiStatus, number(snapshot.body.bmi), '', true); bmi.scale = scaleLayout(snapshot.body.bmiScale)
      const fat = row('体脂率 · ' + snapshot.body.fatStatus, number(snapshot.body.fatPercent, '%'), '', true); fat.scale = scaleLayout(snapshot.body.fatScale)
      bmi.scaleNote = snapshot.body.bmiScale === undefined ? '参考数轴尚未同步，请获取更新' : !bmi.scale ? '当前资料暂无适用的成人参考数轴' : ''
      fat.scaleNote = snapshot.body.fatScale === undefined ? '参考数轴尚未同步，请获取更新' : !fat.scale ? '请在手机完善年龄与性别；当前资料暂无适用数轴' : ''
    } else row('身体参考待同步', '请连接手机获取更新，以显示 BMI 与体脂率数轴')
  } else if (page === 'energy') {
    energyCard('')
    // The phone lists the sources of the day's burn and intake with their own figures: "reference kcal"
    // alone does not say where the energy came from, and the watch now has every part of it.
    row('静息代谢', kcal(snapshot.energy.restingKcal), '', true)
    row('运动', kcal(snapshot.energy.workoutKcal), '', true)
    row('步数', kcal(snapshot.energy.stepsKcal), '', true)
    if (!Array.isArray(snapshot.energy.mealKcal)) row('三餐摄入待同步', '请连接手机获取更新')
    else ['早餐', '午餐', '晚餐', '加餐'].forEach((label, index) => row(label, kcal(snapshot.energy.mealKcal[index]), '', true))
    row('参考口径', '代谢＋运动＋步数＝每日消耗参考，不会重复计算同一次步行；' + '剩余＝消耗参考−登记摄入。')
  } else if (page === 'exercise') {
    snapshot.exercises.forEach(item => {
      const parts = []
      if (item.minutes !== null) parts.push(number(item.minutes, ' 分钟'))
      if (item.distanceKm !== null) parts.push(number(item.distanceKm, ' km'))
      if (item.sets !== null) parts.push(item.sets + ' 组' + (item.reps !== null ? ' × ' + item.reps + ' 次' : ''))
      if (item.loadKg !== null) parts.push(number(item.loadKg, ' kg'))
      parts.push(item.kcal === null ? '待估算' : kcal(item.kcal))
      // Mirroring the phone's workout card: the day's total already contains these steps, or it does
      // not, and either way the user has to be told which.
      const counted = item.includedInSteps && typeof item.steps === 'number'
      const note = counted ? '本次约 ' + item.steps + ' 步已包含在当天总步数中'
        : item.includedInSteps ? '未填写本次步数，按未包含处理；本次步数未计入当天总步数'
        : '本次步数未计入当天总步数'
      row(item.name, ({ aerobic: '有氧', anaerobic: '无氧', unspecified: '未分类' })[item.category] + '\n' + parts.join(' · ') + '\n' + note)
    })
    if (!snapshot.exerciseCount) row('尚未记录运动', '在手机添加当天运动')
    if (snapshot.omittedExercises) row('更多记录', '另有 ' + snapshot.omittedExercises + ' 项，请在手机查看')
  } else if (page === 'meals') {
    snapshot.meals.slice(0, 3).forEach(meal => row(({ breakfast: '早餐', lunch: '午餐', dinner: '晚餐' })[meal.kind], meal.count ? meal.count + ' 项 · ' + kcal(meal.kcal) : '尚未记录', 'meal:' + meal.kind, true))
  } else if (page.indexOf('meal:') === 0) {
    const meal = snapshot.meals.filter(item => item.kind === page.slice(5))[0]
    if (meal) {
      meal.foods.forEach(food => row(food.name, number(food.grams, ' g') + ' · ' + kcal(food.kcal)))
      if (!meal.count) row('尚未记录', '在手机录入这一餐后获取更新')
      if (meal.omitted) row('更多食物', '另有 ' + meal.omitted + ' 项，请在手机查看')
      const snack = snapshot.meals[3]
      if (meal.kind === 'dinner' && snack.count) row('加餐', snack.count + ' 项 · ' + kcal(snack.kcal), 'meal:snack')
    }
  } else if (state && state.kind === 'trend') {
    trendPage()
  } else if (state && state.kind === 'measure') {
    measurePage()
  } else if (page === 'advice') {
    if (!snapshot.advice) row('尚未生成建议', '可在手机生成后手动更新')
    else { paragraphs('吃饭建议', snapshot.advice.content.food); paragraphs('锻炼建议', snapshot.advice.content.exercise) }
  }
  return result
}
export default { CLIENT: CLIENT, checksum: checksum, validate: validate, assembler: assembler, rows: rows, time: time,
  period: period, energy: energy, measure: measure, parse: parse, title: title, sameChart: sameChart,
  validateHistory: validateHistory }
