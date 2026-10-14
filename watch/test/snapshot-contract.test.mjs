import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import dashboard from '../src/lib/dashboard.mjs'

// The phone writes this file in WatchSnapshotContractTest, so it is the phone's real output rather
// than a hand-written copy of what we believe the phone sends. If a field is renamed or retyped on
// either side, this test or the Kotlin one fails instead of the watch quietly dropping a day.
//
// schema 3 replaced the 14-entry `trend` array with the sparse `history` object (days that hold a
// record, plus the weight and body-fat series) and gave every exercise a nullable `steps`. The charts
// below are drawn from that history, so the columns the watch aggregates must be the columns the
// phone computed.
const snapshot = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'snapshot-v1.json'), 'utf8'))
const WEEK = 'trend:meals:week:' + snapshot.day
const PAGES = ['trend:meals:week', 'trend:exercise:month', 'trend:balance:year', 'measure:weight:month', 'measure:fat:year'].map(name => name + ':' + snapshot.day)

test('the snapshot the phone produces passes the watch validator', () => {
  assert.equal(dashboard.validate(snapshot), snapshot)
  assert.equal(snapshot.schema, 3)
})

test('the daily steps row shows both the total and the split the phone computed', () => {
  const row = dashboard.rows(snapshot, 'home').filter(item => item.title === '每日步数')[0]
  assert.equal(row.body, '10000 步\n运动 3000 + 记录 7000')
})

test('the phone step deduction and the watch reading of it agree on the energy figures', () => {
  const rows = dashboard.rows(snapshot, 'energy')
  const body = title => rows.filter(item => item.title === title)[0]
  // 1648.75 kcal of resting metabolism, 441.875 from the two workouts and 171.5 from walking over the
  // remaining 7000 steps. The watch rounds these to whole kilocalories.
  assert.equal(body('静息代谢').body, '1649 千卡')
  assert.equal(body('运动').body, '442 千卡')
  assert.equal(body('步数').body, '172 千卡')
  assert.equal(body('早餐').body, '175 千卡')
  assert.equal(body('午餐').body, '248 千卡')
  assert.equal(body('晚餐').body, '0 千卡')
  assert.equal(body('加餐').body, '0 千卡')
  assert.equal(rows.filter(item => item.title === '每日能量参考')[0].body, '消耗 2262 千卡\n登记摄入 423 千卡')
})

test('the history the phone writes keeps the keys and types the watch reads', () => {
  assert.deepEqual(Object.keys(snapshot.history).sort(), ['days', 'fats', 'weights'])
  assert.deepEqual(Object.keys(snapshot.history.days[0]).sort(), ['activityKcal', 'day', 'intakeKcal', 'referenceKcal'])
  assert.deepEqual(Object.keys(snapshot.history.weights[0]).sort(), ['day', 'value'])
  assert.deepEqual(Object.keys(snapshot.history.fats[0]).sort(), ['day', 'value'])
  assert.equal(typeof snapshot.today, 'string')
  snapshot.exercises.forEach(item => {
    assert.ok(['number', 'object'].indexOf(typeof item.steps) >= 0, 'each exercise carries a nullable steps')
    assert.ok(item.steps === null || Number.isInteger(item.steps))
  })
})

test('every chart page renders the phone history as calendar columns', () => {
  const expected = { 'trend:meals:week': 7, 'trend:exercise:month': 31, 'trend:balance:year': 12,
    'measure:weight:month': 31, 'measure:fat:year': 12 }
  PAGES.forEach(page => {
    const items = dashboard.rows(snapshot, page)
    const chart = items.filter(item => item.bars && item.bars.length)[0]
    assert.ok(chart, page + ' rendered no bars')
    assert.equal(chart.bars.length, expected[page.slice(0, page.lastIndexOf(':'))], page + ' renders one bar per calendar column')
    assert.equal(chart.marker, true, page + ' marks the column it is showing')
    assert.ok(chart.axisTop && chart.axisBottom, page + ' shows both axis bounds')
    chart.bars.forEach(bar => {
      assert.ok(bar.height >= 3 && bar.height <= 120, page + ' keeps every bar inside the plot')
      assert.ok(bar.top >= 0 && bar.top + bar.height <= 120, page + ' keeps every bar inside the plot')
      assert.match(bar.color, /^#[0-9A-Fa-f]{6}$/)
      assert.ok(bar.width >= 6 && bar.width <= 40)
    })
    assert.equal(chart.bars.filter(bar => bar.labelClass === 'trend-label trend-label-on').length, 1, page + ' marks exactly one column')
  })
})

test('the phone history is aggregated into the column the day falls in', () => {
  const chart = dashboard.rows(snapshot, WEEK).filter(item => item.bars)[0]
  // 2026-10-12 is the Monday of the week shown, and it is the only day of it the phone has a record
  // for: 422.5 kcal of intake. 10/13 onwards is after the phone's today.
  assert.match(dashboard.rows(snapshot, WEEK).map(item => item.title).join('|'), /10\/12 周一/)
  const readout = dashboard.rows(snapshot, WEEK).filter(item => item.title === '10/12 周一' && item.highlight)[0]
  assert.equal(readout.body, '423 千卡')
  const year = dashboard.rows(snapshot, 'trend:balance:year:' + snapshot.day).filter(item => item.bars)[0]
  assert.equal(year.bars[9].label, '10月')
  assert.ok(year.bars[9].height > 3)
})

test('the weight and body-fat charts come from the phone history', () => {
  const weight = dashboard.rows(snapshot, 'measure:weight:week:' + snapshot.day)
  const fat = dashboard.rows(snapshot, 'measure:fat:month:' + snapshot.day)
  assert.equal(weight.filter(item => item.bars)[0].bars.length, 7)
  assert.match(weight.map(item => item.title + ' ' + item.body).join('|'), /10\/12/)
  assert.match(weight.map(item => item.title + ' ' + item.body).join('|'), /70 kg/)
  assert.match(fat.map(item => item.title + ' ' + item.body).join('|'), /21\.5%/)
  // The axis must not start at 0, or a 1.5 kg change would be invisible.
  const caption = weight.filter(item => /^纵轴 /.test(item.title))[0]
  assert.match(caption.title, /纵轴 6[0-9](\.[0-9])?–7[0-9](\.[0-9])? kg/)
  assert.doesNotMatch(caption.title, /纵轴 0/)
})

test('the trend tabs carry the same action the page list accepts', () => {
  PAGES.forEach(page => {
    const items = dashboard.rows(snapshot, page)
    const tabs = items.filter(item => item.tab)
    assert.equal(tabs.length, page.indexOf('measure:') === 0 ? 2 : 3, page + ' renders its own tabs')
    assert.equal(tabs.filter(item => item.active).length, 1, page + ' marks exactly one tab')
    assert.equal(tabs.filter(item => item.active)[0].action, page)
    tabs.forEach(tab => assert.ok(dashboard.parse(tab.action), tab.action + ' is a page the router accepts'))
    assert.equal(items.filter(item => item.choices).length, 1, page + ' offers the three scales')
    assert.equal(items.filter(item => item.periodText).length, 1, page + ' shows the period it is on')
  })
})

test('a day the phone has no history for is reported rather than drawn empty', () => {
  const older = Object.assign({}, snapshot, { history: undefined })
  const items = dashboard.rows(older, 'trend:exercise:week:' + snapshot.day)
  assert.equal(items.filter(item => item.bars).length, 0)
  assert.ok(items.some(item => item.title === '趋势数据待同步'))
})
