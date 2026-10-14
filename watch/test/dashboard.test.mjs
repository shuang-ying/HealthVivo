// Tests for the dashboard view model: the optional fields the phone adds on top of schema 2, the
// daily-steps split, and the three trend pages (tab strip, bars, and the "not synced" fallback).
//
// `validate` must keep accepting a snapshot that predates every one of these fields, so the base
// fixture below carries none of them and each addition is layered on explicitly.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import dashboard from '../src/lib/dashboard.mjs'

const KINDS = ['breakfast', 'lunch', 'dinner', 'snack']
const TREND_PAGES = ['trend:meals', 'trend:exercise', 'trend:balance']

function snapshot(overrides) {
  const value = {
    schema: 2, client: 'com.lrh.healthvivo.watch', day: '2026-10-14', revision: 7, generatedAt: 1760400000000,
    steps: 8000, weightKg: 70.5, targetWeightKg: 65,
    energy: { intakeKcal: 1500, restingKcal: 1600, activityKcal: 300, referenceKcal: 1900, pendingCount: 0, overlapHandled: true },
    meals: KINDS.map(kind => ({ kind: kind, count: 0, omitted: 0, kcal: null, foods: [] })),
    exerciseCount: 0, omittedExercises: 0, exercises: [], advice: null
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
function trendPoints() {
  const points = []
  for (let index = 0; index < 14; index++) {
    points.push({ day: '2026-10-' + ('0' + (index + 1)).slice(-2), intakeKcal: 1200 + index * 100,
      activityKcal: 200 + index * 20, referenceKcal: 1800 + index * 50 })
  }
  return points
}

function homeRows(overrides) { return dashboard.rows(dashboard.validate(snapshot(overrides)), 'home') }
function trendRows(page, overrides) { return dashboard.rows(dashboard.validate(snapshot(overrides)), page) }

test('a snapshot without any of the new fields still validates', () => {
  const value = dashboard.validate(snapshot())
  assert.equal(value.steps, 8000)
  assert.equal(value.energy.intakeKcal, 1500)
  assert.equal(value.trend, undefined)
  assert.equal(value.workoutSteps, undefined)
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

test('a trend validates and malformed trends are rejected', () => {
  assert.equal(dashboard.validate(snapshot({ trend: trendPoints() })).trend.length, 14)
  assert.equal(dashboard.validate(snapshot({ trend: [] })).trend.length, 0)
  const single = [{ day: '2026-10-14', intakeKcal: 0, activityKcal: 0, referenceKcal: null }]
  assert.equal(dashboard.validate(snapshot({ trend: single })).trend.length, 1)
  const long = trendPoints().concat([{ day: '2026-10-15', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }])
  assert.throws(() => dashboard.validate(snapshot({ trend: long })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: 'x' })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: {} })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: [{ day: '2026-10-13', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }] })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: [{ day: '2026-10-13', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 },
    { day: '2026-10-12', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }] })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: [{ day: '2026-10-14', intakeKcal: -1, activityKcal: 1, referenceKcal: 1 }] })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: [{ day: '2026-10-14', intakeKcal: 1, activityKcal: 1 }] })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: [{ day: '2026-10-14', intakeKcal: 1, activityKcal: 1, referenceKcal: -1 }] })), /trend/)
  assert.throws(() => dashboard.validate(snapshot({ trend: [{ day: '14-10-2026', intakeKcal: 1, activityKcal: 1, referenceKcal: 1 }] })), /trend/)
})

test('the daily steps row splits the total only when workout steps are present', () => {
  assert.equal(homeRows({})[0].body, '8000 步')
  assert.equal(homeRows({ workoutSteps: 0 })[0].body, '8000 步')
  assert.equal(homeRows({ workoutSteps: 3000 })[0].body, '8000 步\n运动 3000 + 记录 5000')
  assert.equal(homeRows({ workoutSteps: 3000, recordedSteps: 4800 })[0].body, '8000 步\n运动 3000 + 记录 4800')
  assert.equal(homeRows({ steps: 2000, workoutSteps: 3000 })[0].body, '2000 步\n运动 3000 + 记录 0')
  assert.equal(homeRows({ steps: null, workoutSteps: 3000 })[0].body, '未记录')
})

test('the home page offers the trend card', () => {
  const offered = homeRows({}).filter(item => item.action === 'trend:meals')
  assert.equal(offered.length, 1)
  assert.equal(offered[0].title, '趋势')
  assert.match(offered[0].body, /饮食/)
})

test('each trend page renders one bar per day and marks its own tab', () => {
  TREND_PAGES.forEach(page => {
    const items = trendRows(page, { trend: trendPoints() })
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
    assert.equal(charts[0].bars.length, 14)
    assert.equal(items.filter(item => /^近 \d+ 天$/.test(item.title)).length, 1, page + ' captions the range')
    charts[0].bars.forEach(bar => {
      assert.ok(bar.height >= 3 && bar.height <= 120, page + ' keeps every bar inside the plot')
      assert.ok(bar.width >= 6 && bar.width <= 40)
      assert.match(bar.color, /^#[0-9A-F]{6}$/)
      assert.match(bar.label, /^\d{2}$/)
    })
    assert.ok(charts[0].bars.some(bar => bar.height === 120), page + ' scales the largest day to the full plot')
  })
})

test('the balance page colours deficits apart from surpluses and keeps a visible stub for zero', () => {
  const points = [
    { day: '2026-10-11', intakeKcal: 2000, activityKcal: 300, referenceKcal: 1800 },
    { day: '2026-10-12', intakeKcal: 1000, activityKcal: 300, referenceKcal: 1800 },
    { day: '2026-10-13', intakeKcal: 1800, activityKcal: 300, referenceKcal: 1800 },
    { day: '2026-10-14', intakeKcal: 1800, activityKcal: 300, referenceKcal: null }
  ]
  const bars = trendRows('trend:balance', { trend: points }).filter(item => item.bars)[0].bars
  assert.equal(bars.length, 4)
  assert.equal(bars[0].color, '#913643')
  assert.equal(bars[1].color, '#19633F')
  assert.equal(bars[2].color, '#51485E')
  assert.equal(bars[3].color, '#3A3448')
  assert.equal(bars[2].height, 3)
  assert.equal(bars[3].height, 3)
  assert.ok(bars[1].height > bars[0].height)
})

test('a day without a trend says it is not synced instead of drawing an empty chart', () => {
  const cases = [{}, { trend: [] }]
  cases.forEach(overrides => TREND_PAGES.forEach(page => {
    const items = trendRows(page, overrides)
    assert.equal(items.filter(item => item.bars).length, 0, page + ' draws no bars without a trend')
    assert.equal(items.filter(item => item.tab).length, 3)
    const text = items.map(item => item.title + ' ' + item.body).join(' | ')
    assert.match(text, /趋势数据待同步/)
    assert.match(text, /更新到手表/)
  }))
})

test('a snapshot shaped like an empty day from the phone validates and renders', () => {
  const value = dashboard.validate(snapshot({
    steps: null, workoutSteps: 0, recordedSteps: null,
    trend: [{ day: '2026-10-14', intakeKcal: 0, activityKcal: 0, referenceKcal: null }],
    energy: { intakeKcal: 0, restingKcal: null, activityKcal: null, referenceKcal: null, pendingCount: 2, overlapHandled: true,
      stepsKcal: null, workoutKcal: null, mealKcal: [0, 0, 0, 0] }
  }))
  assert.equal(value.steps, null)
  assert.equal(dashboard.rows(value, 'home')[0].body, '未记录')
  const bars = dashboard.rows(value, 'trend:balance').filter(item => item.bars)[0].bars
  assert.equal(bars.length, 1)
  assert.equal(bars[0].color, '#3A3448')
  assert.equal(bars[0].height, 3)
})
