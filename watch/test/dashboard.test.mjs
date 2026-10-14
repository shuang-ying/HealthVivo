// Tests for the dashboard view model: the optional fields the phone adds on top of the schema, the
// daily-steps split, the calendar trend model (week/month/year columns, the axis, the column that is
// selected) and the two new measurement charts.
//
// `validate` must keep accepting a snapshot whose history is empty and which omits every optional
// field, so the base fixture below carries only the required keys and each addition is layered on
// explicitly. The snapshot's own `today` is what decides which columns have happened yet: a day after
// it may not appear in the history at all.
//
// The series is app-wide: `history` travels with the newest day's snapshot (and is an explicit null on
// every other day), so `rows` takes it as its own argument and the tests below pass it that way - the
// page must never be drawn from the day that happens to be on screen.
//
// The arithmetic itself is pinned against the phone by trend-vectors.test.mjs; these tests pin the
// rows the page renders.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import dashboard from '../src/lib/dashboard.mjs'

const KINDS = ['breakfast', 'lunch', 'dinner', 'snack']
const DAY = '2026-10-14'
const TODAY = '2026-10-14'
const SERIES_PAGES = ['trend:meals:week', 'trend:exercise:week', 'trend:balance:week'].map(name => name + ':' + DAY)

function snapshot(overrides) {
  const value = {
    schema: 3, client: 'com.lrh.healthvivo.watch', day: DAY, today: TODAY, revision: 7, generatedAt: 1760400000000,
    steps: 8000, weightKg: 70.5, targetWeightKg: 65,
    energy: { intakeKcal: 1500, restingKcal: 1600, activityKcal: 300, referenceKcal: 1900, pendingCount: 0, overlapHandled: true },
    meals: KINDS.map(kind => ({ kind: kind, count: 0, omitted: 0, kcal: null, foods: [] })),
    exerciseCount: 0, omittedExercises: 0, exercises: [], advice: null,
    history: { days: [], weights: [], fats: [] }
  }
  if (overrides) Object.keys(overrides).forEach(key => { value[key] = overrides[key] })
  return value
}

/** A snapshot whose energy block also carries the optional fields under test. */
function withEnergy(extra) {
  const value = snapshot()
  Object.keys(extra).forEach(key => { value.energy[key] = extra[key] })
  return value
}

/** Fourteen ascending days ending on the fixture's own day. */
function historyDays() {
  const days = []
  for (let index = 0; index < 14; index++) {
    days.push({ day: '2026-10-' + ('0' + (index + 1)).slice(-2), intakeKcal: 1200 + index * 100,
      activityKcal: 200 + index * 20, referenceKcal: 1800 + index * 50 })
  }
  return days
}
function history(days, weights, fats) { return { days: days || [], weights: weights || [], fats: fats || [] } }
// The series is an input of its own: the phone sends it with the newest day's snapshot alone, so the
// page is handed whatever the archive is holding rather than anything read out of the day on screen.
function homeRows(overrides) { return dashboard.rows(dashboard.validate(snapshot(overrides)), 'home') }
function pageRows(page, overrides) { const value = dashboard.validate(snapshot(overrides)); return dashboard.rows(value, page, value.history) }
function chartOf(page, overrides) { return pageRows(page, overrides).filter(item => item.bars)[0] }
function exercise(steps, includedInSteps) {
  return { name: '跑步', category: 'aerobic', minutes: 32, distanceKm: null, sets: null, reps: null, loadKg: null,
    kcal: 350, net: true, estimated: false, includedInSteps: includedInSteps, steps: steps }
}
/** A day list long enough to trip the cap, whatever the calendar does. */
function longDays(count) {
  const days = []
  const start = Date.UTC(2026, 0, 1)
  for (let index = 0; index < count; index++) days.push(new Date(start + index * 86400000).toISOString().slice(0, 10))
  return days
}

test('a snapshot with an empty history and none of the optional fields still validates', () => {
  const value = dashboard.validate(snapshot())
  assert.equal(value.steps, 8000)
  assert.equal(value.energy.intakeKcal, 1500)
  assert.deepEqual(value.history, { days: [], weights: [], fats: [] })
  assert.equal(value.workoutSteps, undefined)
})

test('schema 2 and a missing today are refused rather than half-read', () => {
  assert.throws(() => dashboard.validate(snapshot({ schema: 2 })), /snapshot/)
  const older = snapshot(); delete older.today
  assert.throws(() => dashboard.validate(older), /snapshot/)
  assert.throws(() => dashboard.validate(snapshot({ today: '2026/10/14' })), /snapshot/)
})

test('the step split fields validate and malformed values are rejected', () => {
  assert.equal(dashboard.validate(snapshot({ workoutSteps: 3000 })).workoutSteps, 3000)
  assert.equal(dashboard.validate(snapshot({ steps: null, workoutSteps: 3000 })).workoutSteps, 3000)
  assert.equal(dashboard.validate(snapshot({ workoutSteps: 3000, recordedSteps: 5000 })).recordedSteps, 5000)
  assert.equal(dashboard.validate(snapshot({ workoutSteps: 0, recordedSteps: 0 })).recordedSteps, 0)
  assert.throws(() => dashboard.validate(snapshot({ workoutSteps: -1 })), /steps/)
  assert.throws(() => dashboard.validate(snapshot({ workoutSteps: 12.5 })), /steps/)
  assert.throws(() => dashboard.validate(snapshot({ workoutSteps: '3000' })), /steps/)
  assert.throws(() => dashboard.validate(snapshot({ recordedSteps: -1 })), /steps/)
  assert.throws(() => dashboard.validate(snapshot({ recordedSteps: 8001 })), /steps/)
})

// The phone sends `n(activity.recordedSteps)`, which is JSON null on any day whose steps were never
// recorded. Rejecting the null would throw away the whole day, so null counts as "not derived".
test('a null step count from the phone behaves like an absent one', () => {
  const value = dashboard.validate(snapshot({ steps: null, workoutSteps: 0, recordedSteps: null }))
  assert.equal(value.recordedSteps, null)
  assert.equal(homeRows({ workoutSteps: 0, recordedSteps: null })[0].body, '8000 步')
  assert.equal(homeRows({ workoutSteps: 3000, recordedSteps: null })[0].body, '8000 步\n运动 3000 + 记录 5000')
  assert.equal(homeRows({ steps: null, workoutSteps: 3000, recordedSteps: null })[0].body, '未记录')
})

test('the new energy fields validate and malformed values are rejected', () => {
  assert.equal(dashboard.validate(withEnergy({ stepsKcal: 210, workoutKcal: 90 })).energy.stepsKcal, 210)
  assert.equal(dashboard.validate(withEnergy({ stepsKcal: null, workoutKcal: null })).energy.workoutKcal, null)
  assert.equal(dashboard.validate(withEnergy({ mealKcal: [400, 600, 500, 100] })).energy.mealKcal.length, 4)
  assert.throws(() => dashboard.validate(withEnergy({ stepsKcal: -1 })), /energy/)
  assert.throws(() => dashboard.validate(withEnergy({ stepsKcal: '210' })), /energy/)
  assert.throws(() => dashboard.validate(withEnergy({ workoutKcal: -0.5 })), /energy/)
  assert.throws(() => dashboard.validate(withEnergy({ mealKcal: [400, 600, 500] })), /energy/)
  assert.throws(() => dashboard.validate(withEnergy({ mealKcal: [400, 600, 500, -1] })), /energy meal/)
  assert.throws(() => dashboard.validate(withEnergy({ mealKcal: [400, 600, 500, '100'] })), /energy meal/)
})

// The history replaced the old 14-entry trend array. Every rule about which day counts was applied on
// the phone, so the watch only has to refuse a history it cannot aggregate: wrong types, a day it
// cannot have derived a figure for, an order that would change which reading is "the last of the
// month", or a list long enough to exhaust the watch's memory.
test('a history validates and a malformed one is rejected', () => {
  const full = history(historyDays(), [{ day: '2026-10-05', value: 71 }], [{ day: '2026-10-12', value: 21.5 }])
  assert.equal(dashboard.validate(snapshot({ history: full })).history.days.length, 14)
  assert.equal(dashboard.validate(snapshot({ history: history([]) })).history.days.length, 0)
  assert.equal(dashboard.validate(snapshot({ history: history([{ day: DAY, intakeKcal: 0, activityKcal: 0, referenceKcal: null }]) })).history.days.length, 1)
  // A weight may be dated after the snapshot's today - the phone keeps the whole series, not only the
  // days up to the one being viewed - so only the record order is checked for the measurements.
  assert.equal(dashboard.validate(snapshot({ history: history([], [{ day: '2026-10-20', value: 70 }]) })).history.weights.length, 1)
  // The phone attaches the series to its own today's snapshot and sends an explicit null with every
  // other day, so null is a value the watch has to accept. A key that is missing altogether is still
  // refused: only the phone's own two shapes are legal.
  assert.equal(dashboard.validate(snapshot({ history: null })).history, null)
  const absent = snapshot(); delete absent.history
  assert.throws(() => dashboard.validate(absent), /history/)
  // A history read back from the watch's own storage has no snapshot left to be bounded by: every day
  // it holds was legal when the phone sent it, so only its shape, its order and its caps are checked.
  const carried = history([{ day: '2027-01-02', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }])
  dashboard.validateHistory(carried)
  assert.equal(carried.days.length, 1)
  assert.throws(() => dashboard.validateHistory({ days: [{ day: '2027-01-02', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 },
    { day: '2027-01-01', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }], weights: [], fats: [] }), /history day/)
  assert.throws(() => dashboard.validateHistory(null), /history/)

  const cases = [
    ['no history at all', undefined],
    ['history is an array', []],
    ['days is not a list', { days: 'x', weights: [], fats: [] }, null],
    ['weights is missing', { days: [], fats: [] }, null],
    ['fats is not a list', { days: [], weights: [], fats: 'x' }, null],
    ['a day is not a date', history([{ day: '14-10-2026', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }]), null],
    ['a day is after today', history([{ day: '2026-10-15', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }]), null],
    ['days are out of order', history([{ day: '2026-10-13', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 },
      { day: '2026-10-12', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }]), null],
    ['a day repeats', history([{ day: '2026-10-12', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 },
      { day: '2026-10-12', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }]), null],
    ['intake is missing', history([{ day: DAY, activityKcal: 1, referenceKcal: 1 }]), null],
    ['intake is null', history([{ day: DAY, intakeKcal: null, activityKcal: 1, referenceKcal: 1 }]), null],
    ['intake is a string', history([{ day: DAY, intakeKcal: '520', activityKcal: 1, referenceKcal: 1 }]), null],
    ['intake is negative', history([{ day: DAY, intakeKcal: -1, activityKcal: 1, referenceKcal: 1 }]), null],
    ['activity is missing', history([{ day: DAY, intakeKcal: 1, referenceKcal: 1 }]), null],
    ['reference is negative', history([{ day: DAY, intakeKcal: 1, activityKcal: 1, referenceKcal: -1 }]), null],
    ['reference is a string', history([{ day: DAY, intakeKcal: 1, activityKcal: 1, referenceKcal: '1800' }]), null],
    ['a weight day is not a date', history([], [{ day: 'x', value: 70 }]), null],
    ['weights are out of order', history([], [{ day: '2026-10-12', value: 70 }, { day: '2026-10-05', value: 71 }]), null],
    ['a weight is null', history([], [{ day: '2026-10-05', value: null }]), null],
    ['a weight is negative', history([], [{ day: '2026-10-05', value: -70 }]), null],
    ['body fats are out of order', history([], [], [{ day: '2026-10-12', value: 21.5 }, { day: '2026-10-08', value: 22 }]), null],
    ['a body fat is a string', history([], [], [{ day: '2026-10-08', value: '22' }]), null],
    ['more days than the cap', history(longDays(501).map(day => ({ day: day, intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }))), null],
    ['more weights than the cap', history([], longDays(501).map(day => ({ day: day, value: 70 }))), null],
    ['more body fats than the cap', history([], [], longDays(501).map(day => ({ day: day, value: 20 }))), null]
  ]
  cases.forEach(item => {
    const value = snapshot({ history: item[1] })
    assert.throws(() => dashboard.validate(value), /history/, item[0] + ' is rejected')
  })
})

test('the daily steps row splits the total only when workout steps are present', () => {
  assert.equal(homeRows({})[0].body, '8000 步')
  assert.equal(homeRows({ workoutSteps: 0 })[0].body, '8000 步')
  assert.equal(homeRows({ workoutSteps: 3000 })[0].body, '8000 步\n运动 3000 + 记录 5000')
  assert.equal(homeRows({ workoutSteps: 3000, recordedSteps: 4800 })[0].body, '8000 步\n运动 3000 + 记录 4800')
  assert.equal(homeRows({ steps: 2000, workoutSteps: 3000 })[0].body, '2000 步\n运动 3000 + 记录 0')
  assert.equal(homeRows({ steps: null, workoutSteps: 3000 })[0].body, '未记录')
})

test('the home page offers the energy and the measurement trend cards', () => {
  const energy = homeRows({}).filter(item => item.action === 'trend:meals:week:' + DAY)
  assert.equal(energy.length, 1)
  assert.equal(energy[0].title, '趋势')
  assert.match(energy[0].body, /饮食/)
  const measure = homeRows({}).filter(item => item.action === 'measure:weight:week:' + DAY)
  assert.equal(measure.length, 1)
  assert.match(measure[0].body, /体重/)
})

test('the period label is the one the phone prints for that scale', () => {
  assert.equal(dashboard.period('2026-10-14', 'week').label, '10/12–10/18')
  assert.equal(dashboard.period('2026-10-14', 'month').label, '2026年10月')
  assert.equal(dashboard.period('2026-10-14', 'year').label, '2026年')
  assert.equal(dashboard.period('2026-10-14', 'week').start, '2026-10-12')
  assert.equal(dashboard.period('2026-10-14', 'week').endExclusive, '2026-10-19')
  assert.equal(dashboard.period('2026-10-14', 'month').endExclusive, '2026-11-01')
  assert.equal(dashboard.period('2026-10-14', 'year').endInclusive, '2026-12-31')
  // The period is the one holding the anchor, so a day in the week of 10/05 opens October.
  assert.equal(dashboard.period('2026-10-05', 'month').label, '2026年10月')
  assert.equal(dashboard.period('2026-10-05', 'week').label, '10/5–10/11')
  assert.equal(dashboard.period('2026-03-02', 'week').label, '3/2–3/8')
  assert.equal(dashboard.period('2024-02-29', 'month').label, '2024年2月')
  assert.equal(dashboard.period('2024-02-29', 'month').endExclusive, '2024-03-01')
})

test('a column is one calendar step wide whatever the scale', () => {
  const days = historyDays()
  const week = dashboard.energy(days, dashboard.period(DAY, 'week'), TODAY)
  const month = dashboard.energy(days, dashboard.period(DAY, 'month'), TODAY)
  const year = dashboard.energy(days, dashboard.period(DAY, 'year'), TODAY)
  assert.equal(week.length, 7)
  assert.equal(month.length, 31)
  assert.equal(year.length, 12)
  assert.equal(month[0].startDay, '2026-10-01')
  assert.equal(year[0].startDay, '2026-01-01')
  assert.deepEqual(week.map(column => column.label), ['一', '二', '三', '四', '五', '六', '日'])
  assert.deepEqual(week.map(column => column.shortText), ['10/12', '10/13', '10/14', '10/15', '10/16', '10/17', '10/18'])
  assert.equal(week[0].rangeText, '10/12 周一')
  assert.equal(month[0].rangeText, '10/1')
  assert.equal(year[9].rangeText, '2026年10月')
  assert.deepEqual(year.map(column => column.label), ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月'])
  // A month is summed from the days that fall in it, and the columns after the anchor's own day are
  // marked as not having happened rather than as measured zeros.
  assert.equal(year[9].intakeKcal, 25900)
  assert.equal(year[9].recorded, true)
  assert.equal(year[10].recorded, false)
  assert.equal(year[10].future, true)
  assert.equal(year[9].future, false)
  assert.equal(week[0].intakeKcal, 2300)
  assert.equal(week[1].recorded, true)
  assert.equal(week[3].recorded, false)
  assert.equal(week[3].future, true)
  // A column with no record carries no reference at all rather than a reference of zero.
  assert.equal(week[3].referenceKcal, null)
  assert.equal(week[0].referenceKcal, 2350)
  const none = dashboard.energy([{ day: '2026-10-12', intakeKcal: 5, activityKcal: 6, referenceKcal: null }], dashboard.period(DAY, 'week'), TODAY)
  assert.equal(none[0].referenceKcal, null)
  assert.equal(none[0].intakeKcal, 5)
})

test('a measurement takes the day of the column, and the last reading of a month', () => {
  const weights = [{ day: '2026-10-05', value: 72 }, { day: '2026-10-12', value: 70 }, { day: '2026-10-14', value: 69.5 }]
  const week = dashboard.measure(weights, dashboard.period(DAY, 'week'), TODAY)
  assert.deepEqual(week.map(point => point.value), [70, null, 69.5, null, null, null, null])
  const month = dashboard.measure(weights, dashboard.period(DAY, 'month'), TODAY)
  assert.equal(month[4].value, 72)
  assert.equal(month[11].value, 70)
  assert.equal(month[13].value, 69.5)
  assert.equal(month[14].value, null)
  // A year view collapses a month full of readings into the one it ended on.
  const year = dashboard.measure(weights, dashboard.period(DAY, 'year'), TODAY)
  assert.equal(year[9].value, 69.5)
  assert.equal(year[8].value, null)
  assert.equal(year[10].value, null)
  assert.equal(year[10].future, true)
  const fats = dashboard.measure([{ day: '2026-10-08', value: 22 }, { day: '2026-10-14', value: 21.5 }], dashboard.period(DAY, 'year'), TODAY)
  assert.equal(fats[9].value, 21.5)
})

test('each series page renders one bar per column and marks its own tab', () => {
  SERIES_PAGES.forEach(page => {
    const items = pageRows(page, { history: history(historyDays()) })
    const tabs = items.filter(item => item.tab)
    assert.equal(tabs.length, 3, page + ' renders three tabs')
    assert.deepEqual(tabs.map(item => item.title), ['饮食', '运动', '热量剩余'])
    const active = tabs.filter(item => item.active)
    assert.equal(active.length, 1, page + ' marks exactly one tab')
    assert.equal(active[0].action, page)
    assert.equal(active[0].cardClass, 'tab-on')
    assert.deepEqual(tabs.filter(item => !item.active).map(item => item.cardClass), ['tab-off', 'tab-off'])
    assert.ok(tabs.every(item => item.hint === ''), page + ' hides the row hint on the tabs')
    const charts = items.filter(item => item.bars)
    assert.equal(charts.length, 1, page + ' renders one bar row')
    assert.equal(charts[0].bars.length, 7)
    charts[0].bars.forEach(bar => {
      assert.ok(bar.height >= 3 && bar.height <= 120, page + ' keeps every bar inside the plot')
      assert.ok(bar.top >= 0 && bar.top + bar.height <= 120, page + ' keeps every bar inside the plot')
      assert.ok(bar.width >= 6 && bar.width <= 40)
      assert.match(bar.color, /^#[0-9A-F]{6}$/)
      assert.match(bar.label, /^[一二三四五六日]$/)
    })
    // The top of the axis clears the tallest bar by about 8% of the range: the tallest column is
    // inside the frame but never touching it.
    const tallest = Math.max.apply(null, charts[0].bars.map(bar => bar.height))
    assert.ok(tallest < 120, page + ' leaves the tallest bar off the frame')
    // A chart whose axis floor is zero is anchored at the floor: length alone places the bar, and the
    // tallest one nearly fills the plot. The signed balance axis is checked on its own below.
    if (page !== 'trend:balance:week:' + DAY) {
      assert.ok(tallest >= 105, page + ' scales the largest column to almost the full plot')
      charts[0].bars.forEach(bar => assert.equal(bar.top, 120 - bar.height, page + ' anchors a zero-floor bar at the floor'))
    }
    assert.equal(charts[0].bars.filter(bar => bar.labelClass === 'trend-label trend-label-on').length, 1)
  })
})

// The balance is signed, so its axis is not a zero-based bar axis: the floor is the deepest deficit,
// the top clears the largest surplus, and every bar is measured from the zero line - a deficit hanging
// below it, a surplus rising above it. A bar drawn from the bottom of the plot would put a two-sided
// period on a one-sided axis and quietly draw a deficit as an ordinary upward bar.
test('the balance chart draws a signed axis with the zero line inside the plot', () => {
  const page = 'trend:balance:week:' + DAY + ':6'
  const chartFor = days => chartOf(page, { history: history(days) })
  const record = (day, intake) => ({ day: day, intakeKcal: intake, activityKcal: 0, referenceKcal: 1800 })

  // A deficit-only period: the floor is the deepest deficit, so the zero line is near the top and every
  // bar hangs from it.
  const deficit = chartFor([record('2026-10-12', 2200), record('2026-10-13', 2000)])
  assert.equal(deficit.axisBottom, '轴底 -400 千卡')
  assert.match(deficit.axisTop, /^轴顶 \d/)
  assert.ok(deficit.bars[0].top > 0 && deficit.bars[0].top < 120, 'the zero line sits inside the plot')
  assert.equal(deficit.bars[1].top, deficit.bars[0].top, 'every deficit hangs from the same zero line')
  assert.equal(deficit.bars[0].color, '#913643')
  assert.equal(deficit.bars[0].top + deficit.bars[0].height, 120, 'the deepest deficit reaches the floor')
  assert.ok(deficit.bars[1].top + deficit.bars[1].height < 120)
  // The sign the row would have if a bar were drawn from the bottom: zero only for the bar that reaches
  // the floor, negative for one that does not.
  assert.equal(deficit.bars[0].top - (120 - deficit.bars[0].height), 0)
  assert.ok(deficit.bars[1].top - (120 - deficit.bars[1].height) < 0)

  // A surplus-only period has no deficit to lower the floor, so the floor stays 0 and the bars rise
  // from the bottom exactly as a zero-based chart does.
  const surplus = chartFor([record('2026-10-12', 1000), record('2026-10-13', 1200)])
  assert.equal(surplus.axisBottom, '轴底 0 千卡')
  assert.equal(surplus.bars[0].color, '#19633F')
  assert.equal(surplus.bars[0].top + surplus.bars[0].height, 120)
  assert.equal(surplus.bars[1].top + surplus.bars[1].height, 120)
  assert.equal(surplus.bars[0].top - (120 - surplus.bars[0].height), 0)

  // A period with both: the zero line is shared, the surplus hangs above it and the deficit below, and
  // only the deepest deficit touches the floor.
  const both = chartFor([record('2026-10-12', 2200), record('2026-10-13', 1000), record('2026-10-14', 1800)])
  assert.equal(both.axisBottom, '轴底 -400 千卡')
  assert.ok(Number(both.axisTop.replace(/[^\d.-]/g, '')) > 800, 'the top clears the largest surplus')
  const line = both.bars[1].top + both.bars[1].height
  assert.ok(line > 0 && line < 120, 'the zero line sits inside the plot')
  assert.equal(both.bars[0].top, line, 'the deficit hangs from the zero line')
  // A recorded zero is a visible stub sitting on the zero line, not a zero-height nothing.
  assert.equal(both.bars[2].top + both.bars[2].height, line, 'the zero column sits on the zero line')
  assert.equal(both.bars[2].color, '#51485E')
  assert.equal(both.bars[2].height, 3, 'a recorded zero keeps a visible stub')
  assert.ok(both.bars[1].top - (120 - both.bars[1].height) < 0, 'a surplus is not anchored at the floor')
  assert.equal(both.bars[0].top - (120 - both.bars[0].height), 0, 'the deepest deficit reaches the floor')
  assert.equal(both.bars[0].height, 37)
  assert.equal(both.bars[1].height, 74)
  // A pale stub marks a column with nothing to draw, and it sits on the zero line rather than the floor.
  assert.equal(both.bars[6].top + both.bars[6].height, line)
  assert.equal(both.bars[6].color, '#3A3448')
})

test('the axis clears the tallest column by a fraction of the range and keeps the floor at zero', () => {
  const days = [{ day: '2026-10-05', intakeKcal: 1000, activityKcal: 0, referenceKcal: null }]
  const items = pageRows('trend:meals:month:2026-10-31', { day: '2026-10-31', today: '2026-10-31', history: history(days) })
  const chart = items.filter(item => item.bars)[0]
  assert.equal(chart.axisBottom, '轴底 0 千卡')
  assert.equal(chart.axisTop, '轴顶 1080 千卡')
  assert.equal(Math.max.apply(null, chart.bars.map(bar => bar.height)), 111)
  // The bounds beside the plot are the axis speaking for itself; the caption that used to explain them
  // is gone, and nothing else takes its place.
  assert.equal(items.filter(item => item.title.indexOf('纵轴 ') === 0).length, 0)
  assert.equal(items.filter(item => /起算|浅色短线|点击下方|已选中/.test(item.body)).length, 0)
  const daily = chart.bars.filter(bar => bar.height > 3)
  assert.equal(daily.length, 1)
  assert.equal(daily[0].top, 9, 'the tallest bar clears the frame without touching it')
  assert.equal(daily[0].top + daily[0].height, 120)
})

test('a measurement axis starts just below the lowest reading and never at zero', () => {
  const weights = [{ day: '2026-10-05', value: 72 }, { day: '2026-10-12', value: 70 }]
  const fats = [{ day: '2026-10-08', value: 22 }, { day: '2026-10-14', value: 21.5 }]
  const weight = pageRows('measure:weight:month:' + DAY, { history: history([], weights) })
  const chart = weight.filter(item => item.bars)[0]
  assert.equal(chart.axisBottom, '轴底 69.8 kg')
  assert.equal(chart.axisTop, '轴顶 72.2 kg')
  assert.equal(weight.filter(item => /余量|不强制包含|没有测量的日期/.test(item.body)).length, 0)
  const heights = chart.bars.filter(bar => bar.height > 3).map(bar => bar.height)
  assert.equal(heights.length, 2)
  // A two-kilogram change must not be flattened into the margin.
  assert.ok(Math.abs(heights[1] - heights[0]) > 80)
  assert.ok(heights[1] < 120 && heights[0] > 3)
  // A measurement bar is anchored at the axis floor, which is just below the lowest reading.
  chart.bars.filter(bar => bar.height > 3).forEach(bar => assert.equal(bar.top, 120 - bar.height))
  // A sticky series with a single reading still gets a finite axis around it.
  const flat = pageRows('measure:weight:week:' + DAY, { history: history([], [{ day: DAY, value: 70 }]) }).filter(item => item.bars)[0]
  assert.equal(flat.axisBottom, '轴底 69.9 kg')
  assert.equal(flat.axisTop, '轴顶 70.1 kg')
  assert.ok(flat.bars[2].height > 3 && flat.bars[2].height < 120)
  assert.equal(flat.bars[2].top, 120 - flat.bars[2].height)
  const fat = pageRows('measure:fat:month:' + DAY, { history: history([], [], fats) }).filter(item => item.bars)[0]
  assert.equal(fat.axisBottom, '轴底 21.4%')
  assert.equal(fat.axisTop, '轴顶 22.1%')
  assert.ok(Number(fat.axisBottom.replace(/[^\d.]/g, '')) > 0)
})

test('the value shown by default is the last column that recorded something', () => {
  const page = 'trend:meals:week:' + DAY
  const items = pageRows(page, { history: history(historyDays()) })
  const marked = items.filter(item => item.title === '10/14 周三' && item.highlight && !item.action)
  assert.equal(marked.length, 1)
  assert.equal(marked[0].body, '2500 千卡')
  // 10/15 onwards has not happened: the default must not jump to a column with no record.
  assert.equal(chartOf(page, { history: history(historyDays()) }).marker, true)
  const balance = pageRows('trend:balance:year:' + DAY, { history: history(historyDays()) })
  assert.equal(balance.filter(item => item.title === '2026年10月' && item.highlight && !item.action).length, 1)
  const measure = pageRows('measure:weight:week:' + DAY, { history: history([], [{ day: '2026-10-05', value: 72 }, { day: '2026-10-12', value: 70 }]) })
  assert.equal(measure.filter(item => item.title === '10/12 周一' && item.highlight && !item.action)[0].body, '70 kg')
})

test('a tapped column is the one the page shows, and it is marked in the chart', () => {
  const page = 'trend:meals:month:' + DAY
  const items = pageRows(page + ':4', { history: history(historyDays()) })
  const chart = items.filter(item => item.bars)[0]
  assert.equal(chart.bars[4].color, '#F2C14E')
  assert.equal(chart.bars[4].labelClass, 'trend-label trend-label-on')
  assert.equal(chart.bars.filter(bar => bar.labelClass === 'trend-label trend-label-on').length, 1)
  assert.equal(chart.marker, true)
  assert.ok(chart.markerLeft > 0 && chart.markerLeft < 360)
  assert.equal(items.filter(item => item.title === '10/5' && item.highlight && !item.action)[0].body, '1600 千卡')
  // Nothing in the page's own rows selects a column any more: the dated list under the chart is gone,
  // and the bar itself is the tap target.
  assert.equal(items.filter(item => /:\d+$/.test(item.action)).length, 0)
  assert.equal(chart.bars[4].action, page + ':4')
  // An index past the end of the period is clamped rather than crashing the page.
  assert.equal(pageRows(page + ':40', { history: history(historyDays()) }).filter(item => item.title === '10/31' && item.highlight && !item.action).length, 1)
  // The index is a column of whichever period the anchor lands in: the same index in February, which
  // has fewer columns, names that period's own last day instead of a stale October one.
  const february = pageRows('trend:meals:month:2026-02-14:30', { history: history(historyDays()) })
  assert.equal(february.filter(item => item.periodText === '2026年2月').length, 1)
  assert.equal(february.filter(item => item.title === '2/28' && item.highlight && !item.action).length, 1)
})

// Every column is reachable without a dated list under the chart: the bar for that column is the tap
// target, and the page it names is the same page name the router already accepts.
test('every bar selects its own column and no dated row list is left', () => {
  const barsOf = (base, index, series) => pageRows(index === undefined ? base : base + ':' + index, { history: series || history(historyDays()) })
  const month = barsOf('trend:meals:month:' + DAY).filter(item => item.bars)[0].bars
  assert.equal(month.length, 31)
  month.forEach((bar, index) => {
    assert.equal(bar.action, 'trend:meals:month:' + DAY + ':' + index)
    assert.ok(dashboard.parse(bar.action), 'a bar names a page the router opens')
  })
  // The month's rows are the tabs, the chips, the period, the chart, the readout and its detail: no
  // row is a date the user could tap to switch column, and no hint invites them to.
  const rows = barsOf('trend:meals:month:' + DAY)
  assert.equal(rows.filter(item => /:\d+$/.test(item.action)).length, 0)
  assert.equal(rows.filter(item => /点击下方|任一时段|查看这一天/.test(item.body + item.hint)).length, 0)
  // The selected column is the one the bar names, in every scale and on the measurement pages too.
  const tapped = barsOf('trend:meals:month:' + DAY, 4).filter(item => item.bars)[0].bars
  assert.equal(tapped.filter(bar => bar.color === '#F2C14E').length, 1)
  assert.equal(tapped[4].color, '#F2C14E')
  const reading = history([], [{ day: '2026-10-14', value: 70 }], [])
  const week = barsOf('measure:weight:week:' + DAY, 2, reading).filter(item => item.bars)[0].bars
  assert.equal(week.length, 7)
  assert.equal(week[2].action, 'measure:weight:week:' + DAY + ':2')
  assert.equal(week[2].color, '#F2C14E')
  const year = barsOf('trend:balance:year:' + DAY).filter(item => item.bars)[0].bars
  assert.equal(year[0].action, 'trend:balance:year:' + DAY + ':0')
})

// The cursor is a date, so the scale chips keep the calendar place instead of a period count, and a
// step moves that date by exactly one unit of the scale.
test('the scale choices keep the series and the calendar place', () => {
  const items = pageRows('trend:meals:month:' + DAY, { history: history(historyDays()) })
  const chips = items.filter(item => item.choices)[0]
  assert.deepEqual([chips.left.text, chips.middle.text, chips.right.text], ['周', '月', '年'])
  assert.deepEqual([chips.left.action, chips.middle.action, chips.right.action],
    ['trend:meals:week:' + DAY, 'trend:meals:month:' + DAY, 'trend:meals:year:' + DAY])
  assert.equal(chips.middle.cardClass, 'choice choice-on')
  assert.equal(chips.left.cardClass, 'choice')
  // Standing in the week of 10/05 and choosing 月 opens October 2026, not the month of the snapshot day.
  const week = pageRows('trend:meals:week:2026-10-05', { history: history(historyDays()) })
  assert.equal(week.filter(item => item.periodText)[0].periodText, '10/5–10/11')
  const month = pageRows('trend:meals:month:2026-10-05', { history: history(historyDays()) })
  assert.equal(month.filter(item => item.periodText)[0].periodText, '2026年10月')
  const year = pageRows('trend:meals:year:2025-10-05', { history: history(historyDays()) })
  assert.equal(year.filter(item => item.periodText)[0].periodText, '2025年')
  assert.equal(year.filter(item => item.choices)[0].middle.action, 'trend:meals:month:2025-10-05')
})

test('stepping moves the anchor one unit of the current scale and never past the snapshot day', () => {
  const period = page => pageRows(page, { history: history(historyDays()) }).filter(item => item.periodText)[0]
  const past = period('trend:meals:month:2026-08-14')
  assert.equal(past.periodText, '2026年8月')
  assert.equal(past.previous, 'trend:meals:month:2026-07-14')
  assert.equal(past.next, 'trend:meals:month:2026-09-14')
  assert.equal(past.canNext, true)
  assert.equal(past.reset, 'trend:meals:month:' + DAY)
  // At the present there is no next period and nothing to go back to.
  const current = period('trend:meals:month:' + DAY)
  assert.equal(current.next, '')
  assert.equal(current.canNext, false)
  assert.equal(current.reset, 'trend:meals:month:' + DAY)
  assert.equal(pageRows('trend:meals:month:' + DAY, { history: history(historyDays()) }).filter(item => item.title === '回到当前时段').length, 0)
  assert.equal(pageRows('trend:meals:month:2026-08-14', { history: history(historyDays()) }).filter(item => item.title === '回到当前时段').length, 1)
  // Weeks step by seven days, and a month step clamps to the end of a shorter month.
  assert.equal(period('trend:meals:week:2026-10-05').previous, 'trend:meals:week:2026-09-28')
  assert.equal(period('trend:meals:week:2026-10-05').next, 'trend:meals:week:2026-10-12')
  assert.equal(period('trend:meals:month:2024-03-31').previous, 'trend:meals:month:2024-02-29')
  // The forward step stops at the period holding the snapshot day: 9/30 + 1 month would be 10/30, which
  // is after the snapshot's own day, so it lands on that day instead of showing a period ahead.
  const edge = dashboard.validate(snapshot({ day: '2026-10-01', today: '2026-10-01' }))
  const before = dashboard.rows(edge, 'trend:meals:month:2026-09-30').filter(item => item.periodText)[0]
  assert.equal(before.canNext, true)
  assert.equal(before.next, 'trend:meals:month:2026-10-01')
  const after = dashboard.rows(edge, before.next).filter(item => item.periodText)[0]
  assert.equal(after.periodText, '2026年10月')
  assert.equal(after.canNext, false)
  // A year step from the last day of a year behaves the same way.
  const january = dashboard.validate(snapshot({ day: '2026-01-01', today: '2026-01-01' }))
  const lastYear = dashboard.rows(january, 'trend:balance:year:2025-12-31').filter(item => item.periodText)[0]
  assert.equal(lastYear.periodText, '2025年')
  assert.equal(lastYear.next, 'trend:balance:year:2026-01-01')
  // An anchor after the snapshot day is clamped to it rather than showing a period that has not happened.
  const ahead = period('trend:meals:month:2027-03-05')
  assert.equal(ahead.periodText, '2026年10月')
  assert.equal(ahead.previous, 'trend:meals:month:2026-09-14')
  assert.equal(ahead.next, '')
  const measure = pageRows('measure:fat:year:2023-10-08', { history: history([], [], [{ day: '2026-10-08', value: 22 }]) })
  assert.deepEqual(measure.filter(item => item.tab).map(item => item.action), ['measure:weight:year:2023-10-08', 'measure:fat:year:2023-10-08'])
  assert.equal(measure.filter(item => item.periodText)[0].periodText, '2023年')
  assert.equal(measure.filter(item => item.periodText)[0].next, 'measure:fat:year:2024-10-08')
})

test('a day without a history says it is not synced instead of drawing an empty chart', () => {
  // rows() is called with the snapshot that failed to validate, the way the page does when the phone
  // has never sent this day: there is no calendar to draw at all.
  const items = dashboard.rows(snapshot({ history: undefined }), 'trend:exercise:week:' + DAY)
  assert.equal(items.filter(item => item.bars).length, 0)
  assert.equal(items.filter(item => item.tab).length, 3)
  assert.equal(items.filter(item => item.choices).length, 1)
  const text = items.map(item => item.title + ' ' + item.body).join(' | ')
  assert.match(text, /趋势数据待同步/)
  assert.match(text, /更新到手表/)
  // The history the phone now sends with every day but the newest is an explicit null, and the watch
  // has never received one: the page reports that rather than drawing an empty chart. This holds for
  // both charts, and the day's own snapshot is irrelevant to it.
  const valued = dashboard.validate(snapshot({ history: null }))
  assert.equal(valued.history, null)
  assert.equal(dashboard.rows(valued, 'trend:exercise:week:' + DAY, null).filter(item => item.bars).length, 0)
  assert.ok(dashboard.rows(valued, 'trend:exercise:week:' + DAY, null).some(item => item.title === '趋势数据待同步'))
  assert.equal(dashboard.rows(valued, 'measure:weight:week:' + DAY, null).filter(item => item.bars).length, 0)
  assert.ok(dashboard.rows(valued, 'measure:fat:week:' + DAY, null).some(item => item.title === '趋势数据待同步'))
  // A history that is present but empty is a calendar with nothing in it, not a missing sync.
  const empty = pageRows('trend:exercise:week:' + DAY, { history: history([]) })
  assert.equal(empty.filter(item => item.bars)[0].bars.length, 7)
  assert.equal(empty.filter(item => item.bars)[0].bars.filter(bar => bar.height > 3).length, 0)
  assert.equal(empty.filter(item => item.title === '这一段没有记录').length, 1)
})

// The series belongs to the app, not to the day on screen. The phone now attaches it to the snapshot
// whose day is its own today and sends null with every other day, so a page drawn from the day's own
// snapshot would simply have no series for the 12th - which is exactly how the 14th's weight went
// missing while the 12th was viewed.
test('a trend page is drawn from the series it is handed, whichever day is on screen', () => {
  const older = dashboard.validate(snapshot({ history: null }))
  const days = historyDays()
  const newer = history(days, [{ day: '2026-10-05', value: 71 }, { day: '2026-10-12', value: 70 }, { day: '2026-10-14', value: 69.5 }],
    [{ day: '2026-10-08', value: 22 }, { day: '2026-10-14', value: 21.5 }])
  // The 12th's own snapshot is an older day: its `today` is the 12th and it carries no series at all.
  assert.equal(older.day, DAY)
  assert.equal(older.today, DAY)
  const week = dashboard.rows(older, 'measure:weight:week:' + DAY, newer)
  const chart = week.filter(item => item.bars)[0]
  assert.equal(chart.bars.length, 7)
  // 10/14 is the Wednesday of that week, and its reading is drawn even though the snapshot on screen
  // knows nothing past the 12th.
  assert.equal(chart.bars[2].color, '#F2C14E')
  assert.notEqual(chart.bars[2].color, '#3A3448')
  assert.equal(week.filter(item => item.title === '10/14 周三' && item.highlight && !item.action)[0].body, '69.5 kg')
  assert.equal(week.filter(item => item.title === '这一段变化')[0].body, '−0.5 kg · 2 次记录')
  const trend = dashboard.rows(older, 'trend:meals:week:' + DAY, newer)
  const bars = trend.filter(item => item.bars)[0].bars
  assert.equal(bars[2].color, '#F2C14E')
  assert.equal(trend.filter(item => item.title === '10/14 周三' && item.highlight && !item.action)[0].body, '2500 千卡')
  // The series handed in is the only one read: a different one changes the page even though the day's
  // own snapshot never changes.
  const other = dashboard.rows(older, 'trend:meals:week:' + DAY, history([{ day: '2026-10-12', intakeKcal: 0, activityKcal: 0, referenceKcal: null }]))
  assert.equal(other.filter(item => item.title === '10/12 周一' && item.highlight && !item.action)[0].body, '0 千卡')
})

test('a series that has not reached the day on screen leaves the tail missing, not zero', () => {
  // The watch keeps the last series it received, and the phone had nothing recorded after the 12th.
  // The day on screen is the 14th, whose own `today` is the 14th: the columns after the 12th have no
  // record, so they are gaps rather than measured zeros, and the readout does not invent one for them.
  const days = []
  for (let index = 0; index < 12; index++) days.push({ day: '2026-10-' + ('0' + (index + 1)).slice(-2),
    intakeKcal: 1200 + index * 100, activityKcal: 200, referenceKcal: 1800 })
  const value = dashboard.validate(snapshot({ history: null }))
  const items = dashboard.rows(value, 'trend:meals:week:' + DAY, history(days, [], []))
  const chart = items.filter(item => item.bars)[0]
  assert.equal(chart.bars.length, 7)
  // 10/12 recorded; 10/13 onwards is past the last day the series holds.
  assert.notEqual(chart.bars[0].color, '#3A3448')
  chart.bars.slice(1).forEach(bar => {
    assert.equal(bar.color, '#3A3448', 'a column with no record is a pale stub')
    assert.equal(bar.height, 3)
    assert.equal(bar.top, 117)
  })
  // The default readout is the last column that recorded something - not a zero for the tail.
  assert.equal(items.filter(item => item.title === '10/12 周一' && item.highlight && !item.action)[0].body, '2300 千卡')
  assert.equal(items.filter(item => item.title === '10/14 周三' && item.highlight).length, 0)
})

test('an exercise row says whether its steps are already in the day total', () => {
  const rows = pageRows('exercise', { exerciseCount: 3, exercises: [exercise(3000, true), exercise(null, false), exercise(null, true)] })
  assert.match(rows[0].body, /本次约 3000 步已包含在当天总步数中/)
  assert.match(rows[1].body, /本次步数未计入当天总步数/)
  assert.match(rows[2].body, /未计入当天总步数/)
  assert.doesNotMatch(rows[2].body, /已包含/)
  assert.equal(dashboard.validate(snapshot({ exerciseCount: 1, exercises: [exercise(3000, true)] })).exercises[0].steps, 3000)
  assert.equal(dashboard.validate(snapshot({ exerciseCount: 1, exercises: [exercise(undefined, false)] })).exercises[0].steps, undefined)
  assert.throws(() => dashboard.validate(snapshot({ exerciseCount: 1, exercises: [exercise('3000', true)] })), /exercise/)
  assert.throws(() => dashboard.validate(snapshot({ exerciseCount: 1, exercises: [exercise(-1, true)] })), /exercise/)
  assert.throws(() => dashboard.validate(snapshot({ exerciseCount: 1, exercises: [exercise(1.5, true)] })), /exercise/)
})

test('the energy page itemises the day the way the phone does', () => {
  const items = pageRows('energy', { energy: { intakeKcal: 1500, restingKcal: 1600, activityKcal: 300, referenceKcal: 1900,
    pendingCount: 0, overlapHandled: true, stepsKcal: 210, workoutKcal: 90, mealKcal: [400, 600, 500, 100] } })
  const body = title => items.filter(item => item.title === title)[0].body
  assert.equal(body('静息代谢'), '1600 千卡')
  assert.equal(body('运动'), '90 千卡')
  assert.equal(body('步数'), '210 千卡')
  assert.deepEqual(['早餐', '午餐', '晚餐', '加餐'].map(body), ['400 千卡', '600 千卡', '500 千卡', '100 千卡'])
  assert.match(items.filter(item => item.title === '参考口径')[0].body, /代谢＋运动＋步数/)
  const plain = pageRows('energy', {})
  assert.deepEqual(plain.filter(item => ['早餐', '午餐', '晚餐', '加餐'].indexOf(item.title) >= 0), [])
  assert.equal(plain.filter(item => item.title === '三餐摄入待同步').length, 1)
})

test('a snapshot shaped like an empty day from the phone validates and renders', () => {
  const value = dashboard.validate(snapshot({
    steps: null, workoutSteps: 0, recordedSteps: null,
    history: history([{ day: DAY, intakeKcal: 0, activityKcal: 0, referenceKcal: null }]),
    energy: { intakeKcal: 0, restingKcal: null, activityKcal: null, referenceKcal: null, pendingCount: 2, overlapHandled: true,
      stepsKcal: null, workoutKcal: null, mealKcal: [0, 0, 0, 0] }
  }))
  assert.equal(value.steps, null)
  assert.equal(dashboard.rows(value, 'home')[0].body, '未记录')
  const items = dashboard.rows(value, 'trend:balance:week:' + DAY, value.history)
  const bars = items.filter(item => item.bars)[0].bars
  assert.equal(bars.length, 7)
  assert.equal(bars[2].color, '#3A3448')
  assert.equal(bars[2].height, 3)
  assert.equal(bars[2].top, 117)
  assert.equal(items.filter(item => item.title === '10/14 周三' && item.highlight)[0].body, '待补全')
  const measure = dashboard.rows(value, 'measure:weight:week:' + DAY, value.history)
  assert.equal(measure.filter(item => item.bars).length, 0)
  assert.equal(measure.filter(item => item.title === '这一段没有记录').length, 1)
})

test('the page names the router accepts, including the two old ones', () => {
  assert.deepEqual(dashboard.parse('trend:meals:month:2026-10-05:12'),
    { kind: 'trend', series: 'meals', scale: 'month', anchor: '2026-10-05', index: 12 })
  assert.deepEqual(dashboard.parse('measure:fat:year:2026-10-05'),
    { kind: 'measure', series: 'fat', scale: 'year', anchor: '2026-10-05', index: null })
  // The names the pages used before the scales existed still resolve to the present week, so a queued
  // action is not a dead path; a page may also name the scale but leave the anchor out.
  assert.deepEqual(dashboard.parse('trend:meals'), { kind: 'trend', series: 'meals', scale: 'week', anchor: null, index: null })
  assert.deepEqual(dashboard.parse('trend:meals:month'), { kind: 'trend', series: 'meals', scale: 'month', anchor: null, index: null })
  assert.equal(dashboard.title('trend:exercise'), '运动趋势')
  assert.equal(dashboard.title('trend:balance:year:2026-10-05'), '热量剩余趋势')
  assert.equal(dashboard.title('measure:fat:week:2026-10-05'), '体脂率趋势')
  assert.equal(dashboard.title('home'), '')
  const rejected = ['', 'home', 'trend', 'trend:', 'trend:meals:', 'trend:meals:decade:', 'trend:meals:decade:2026-10-05',
    'trend:meals:week:0', 'trend:meals:week:-1', 'trend:meals:week:2026-10-05:1:2', 'trend:meals:week:2026-10-05:',
    'trend:meals:week:2026-13-05', 'trend:meals:week:2026-02-31', 'measure:bogus:week:2026-10-05',
    'measure:weight:week:2026-10-05:1000000']
  rejected.forEach(page => assert.equal(dashboard.parse(page), null, page + ' is not a chart page'))
  // Every action a row offers must be a page the router can open, and so must every bar of a chart.
  const value = dashboard.validate(snapshot({ history: history(historyDays(), [{ day: '2026-10-05', value: 71 }], [{ day: '2026-10-08', value: 22 }]) }))
  SERIES_PAGES.concat(['measure:weight:month:' + DAY]).forEach(page => {
    const items = dashboard.rows(value, page, value.history)
    items.forEach(item => {
      if (!item.action) return
      assert.ok(dashboard.parse(item.action) || ['energy', 'exercise', 'meals', 'advice', 'connection'].indexOf(item.action) >= 0,
        item.action + ' is offered but the router would refuse it')
    })
    items.forEach(item => (item.bars || []).forEach(bar => {
      assert.ok(dashboard.parse(bar.action), bar.action + ' is a bar the router would refuse')
    }))
  })
})

// The watch keeps its scroll offset across a redraw that only changes the card's content, and starts
// at the top for a real move. `sameChart` is that decision, kept out of the page so it can be pinned.
test('a redraw inside one card keeps the scroll, a real move starts at the top', () => {
  const chart = 'trend:meals:month:2026-10-14'
  ;['trend:meals:week:2026-10-14', 'trend:exercise:month:2026-10-14', 'trend:balance:month:2026-10-14:3',
    'trend:meals:month:2026-08-14'].forEach(next => assert.equal(dashboard.sameChart(chart, next), true, next + ' stays in the chart'))
  assert.equal(dashboard.sameChart('measure:weight:month:2026-10-14', 'measure:fat:month:2026-10-14'), true)
  assert.equal(dashboard.sameChart('measure:weight:month:2026-10-14', 'measure:weight:year:2026-10-14'), true)
  assert.equal(dashboard.sameChart('trend:meals:week:2026-10-14', 'measure:weight:week:2026-10-14'), false)
  assert.equal(dashboard.sameChart('home', chart), false)
  assert.equal(dashboard.sameChart(chart, 'home'), false)
  assert.equal(dashboard.sameChart('home', 'energy'), false)
  assert.equal(dashboard.sameChart(chart, 'trend:bogus:week:2026-10-14'), false)
})

// The list is the only scroller in the app, so the position it must return to is recorded from the
// list itself. These assertions pin the wiring the runtime offers: an absolute `startoffset`, the
// `onscroll` callback that reports `scrollY` while the user drags (with `scrollindex` as the range the
// watch documents on its own), and one tap target per bar.
test('the list is wired to keep and restore its own scroll offset', () => {
  const template = readFileSync(new URL('../src/Home/index.ux', import.meta.url), 'utf8')
  assert.match(template, /<list id="dashboard-list"[^>]*startoffset="\{\{scrollOffset\}\}"/,
    'the list binds its absolute scroll offset')
  assert.match(template, /<list id="dashboard-list"[^>]*onscroll="onScroll"/,
    'the list reports where it is while the user scrolls it')
  assert.match(template, /<list id="dashboard-list"[^>]*onscrollindex="onScrollIndex"/,
    'the list also reports its visible index range')
  assert.match(template, /for="\{\{bar in card\.bars\}\}"[^>]*onclick="select\(bar\.action\)"/,
    'every bar taps through to its own column')
  assert.match(template, /scrollOffset: ''/, 'the offset starts empty, which is the top')
  // The offset is recorded from the events rather than read when a tap arrives, and it is applied only
  // for a redraw inside the same card.
  const script = template.slice(template.indexOf('<script>'))
  assert.match(script, /onScroll\(event\)[\s\S]*?event\.scrollY[\s\S]*?this\.runtime\.scrollY = value/)
  assert.match(script, /onScrollIndex\(event\)[\s\S]*?event\.first[\s\S]*?this\.runtime\.scrollIndex = value/)
  assert.match(script, /const keep = dashboard\.sameChart\(this\.runtime\.page, page\)/)
  assert.match(script, /restoreScroll\(keep\)[\s\S]*?this\.runtime\.scrollY > 0[\s\S]*?String\(this\.runtime\.scrollY\)/)
  // The fallback restores the item at the top of the view, which the list clamps to its own end.
  assert.match(script, /this\.runtime\.scrollIndex > 0[\s\S]*?scrollTo\(\{ index: this\.runtime\.scrollIndex, behavior: 'instant' \}\)/)
  assert.match(script, /if \(!keep\)[\s\S]*?this\.runtime\.scrollY = 0; this\.runtime\.scrollIndex = 0[\s\S]*?scrollTo\(\{ index: 0 \}\)/)
})

test('the dashboard avoids globals and syntax the watch runtime may not provide', () => {
  // The watch engine has no globalThis, no BigInt, no Array.from and no optional chaining. A missing
  // global is invisible until the exact line that uses it runs, so the constraint is pinned down
  // mechanically. Prose that names one of these constructs is ignored.
  const forbidden = [
    ['globalThis', /\bglobalThis\b/], ['BigInt', /\bBigInt\b/], ['setInterval', /\bsetInterval\b/],
    ['clearInterval', /\bclearInterval\b/], ['optional chaining', /\?\.[A-Za-z_$[]/], ['nullish coalescing', /\?\?/],
    ['flatMap', /\.flatMap\s*\(/], ['Object.entries', /Object\s*\.\s*entries\s*\(/], ['Object.values', /Object\s*\.\s*values\s*\(/],
    ['Array.from', /Array\s*\.\s*from\s*\(/], ['structuredClone', /\bstructuredClone\b/], ['queueMicrotask', /\bqueueMicrotask\b/],
    ['replaceAll', /\.replaceAll\s*\(/], ['padStart', /\.padStart\s*\(/],
    ['unescape', /\bunescape\s*\(/], ['escape', /(^|[^A-Za-z0-9_$.])escape\s*\(/]
  ]
  const files = {
    'dashboard.mjs': readFileSync(new URL('../src/lib/dashboard.mjs', import.meta.url), 'utf8'),
    'index.ux': readFileSync(new URL('../src/Home/index.ux', import.meta.url), 'utf8')
  }
  const problems = []
  Object.keys(files).forEach(name => {
    const code = files[name].replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/(^|[^:])\/\/[^\n]*/g, '$1 ')
    forbidden.forEach(item => { if (item[1].test(code)) problems.push(name + ' uses ' + item[0]) })
  })
  assert.deepEqual(problems, [])
})
