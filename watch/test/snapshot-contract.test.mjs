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
//
// The fixture's day is the phone's own today, which is the one snapshot that carries the series; every
// other day now sends an explicit null. `rows` therefore takes the series as its own argument, and the
// pages here are handed the fixture's history the way the archive hands it whatever day is on screen.
const snapshot = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'snapshot-v1.json'), 'utf8'))
const HISTORY = snapshot.history
const WEEK = 'trend:meals:week:' + snapshot.day
const PAGES = ['trend:meals:week', 'trend:exercise:month', 'trend:balance:year', 'measure:weight:month', 'measure:fat:year'].map(name => name + ':' + snapshot.day)
/** The day's own file, with the series left out exactly as the phone leaves it out of an older day. */
const OLDER = Object.assign({}, snapshot, { history: null })

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
  // The series travels with the newest day's snapshot alone; every other day carries an explicit null,
  // which the watch validator accepts as a value rather than as a missing key.
  assert.ok(HISTORY && Array.isArray(HISTORY.days))
  assert.equal(dashboard.validate(OLDER).history, null)
  snapshot.exercises.forEach(item => {
    assert.ok(['number', 'object'].indexOf(typeof item.steps) >= 0, 'each exercise carries a nullable steps')
    assert.ok(item.steps === null || Number.isInteger(item.steps))
  })
})

test('every chart page renders the phone history as calendar columns', () => {
  const expected = { 'trend:meals:week': 7, 'trend:exercise:month': 31, 'trend:balance:year': 12,
    'measure:weight:month': 31, 'measure:fat:year': 12 }
  PAGES.forEach(page => {
    const items = dashboard.rows(snapshot, page, HISTORY)
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
      assert.ok(dashboard.parse(bar.action), page + ' gives every bar a column to select')
    })
    assert.equal(chart.bars.filter(bar => bar.labelClass === 'trend-label trend-label-on').length, 1, page + ' marks exactly one column')
  })
  // The same pages draw from the series the archive holds even when the day on screen is one of the
  // older files the phone sends with a null history: that is what puts the 12th's snapshot on screen
  // while the 14th's reading is drawn.
  PAGES.forEach(page => {
    const fromOlder = dashboard.rows(OLDER, page, HISTORY)
    const chart = fromOlder.filter(item => item.bars && item.bars.length)[0]
    assert.ok(chart, page + ' rendered no bars from the held series')
    assert.equal(chart.bars.length, expected[page.slice(0, page.lastIndexOf(':'))])
  })
})

test('the phone history is aggregated into the column the day falls in', () => {
  const chart = dashboard.rows(snapshot, WEEK, HISTORY).filter(item => item.bars)[0]
  // 2026-10-12 is the Monday of the week shown, and it is the only day of it the phone has a record
  // for: 422.5 kcal of intake. 10/13 onwards is after the phone's today.
  assert.match(dashboard.rows(snapshot, WEEK, HISTORY).map(item => item.title).join('|'), /10\/12 周一/)
  const readout = dashboard.rows(snapshot, WEEK, HISTORY).filter(item => item.title === '10/12 周一' && item.highlight)[0]
  assert.equal(readout.body, '423 千卡')
  const year = dashboard.rows(snapshot, 'trend:balance:year:' + snapshot.day, HISTORY).filter(item => item.bars)[0]
  assert.equal(year.bars[9].label, '10月')
  assert.ok(year.bars[9].height > 3)
})

test('the weight and body-fat charts come from the phone history', () => {
  const weight = dashboard.rows(snapshot, 'measure:weight:week:' + snapshot.day, HISTORY)
  const fat = dashboard.rows(snapshot, 'measure:fat:month:' + snapshot.day, HISTORY)
  const chart = weight.filter(item => item.bars)[0]
  assert.equal(chart.bars.length, 7)
  assert.match(weight.map(item => item.title + ' ' + item.body).join('|'), /10\/12/)
  assert.match(weight.map(item => item.title + ' ' + item.body).join('|'), /70 kg/)
  assert.match(fat.map(item => item.title + ' ' + item.body).join('|'), /21\.5%/)
  // The axis must not start at 0, or a 1.5 kg change would be invisible. It is the bounds printed
  // beside the plot that say so; the sentence that used to explain them is gone.
  assert.match(chart.axisBottom, /^轴底 6[0-9](\.[0-9])? kg$/)
  assert.match(chart.axisTop, /^轴顶 7[0-9](\.[0-9])? kg$/)
  assert.equal(weight.filter(item => /起算|余量|不强制包含|浅色短线|点击下方/.test(item.title + item.body)).length, 0)
})

test('the trend tabs carry the same action the page list accepts', () => {
  PAGES.forEach(page => {
    const items = dashboard.rows(snapshot, page, HISTORY)
    const tabs = items.filter(item => item.tab)
    assert.equal(tabs.length, page.indexOf('measure:') === 0 ? 2 : 3, page + ' renders its own tabs')
    assert.equal(tabs.filter(item => item.active).length, 1, page + ' marks exactly one tab')
    assert.equal(tabs.filter(item => item.active)[0].action, page)
    tabs.forEach(tab => assert.ok(dashboard.parse(tab.action), tab.action + ' is a page the router accepts'))
    assert.equal(items.filter(item => item.choices).length, 1, page + ' offers the three scales')
    assert.equal(items.filter(item => item.periodText).length, 1, page + ' shows the period it is on')
    // No dated list under the chart: the columns are reached through the bars themselves.
    assert.equal(items.filter(item => /:\d+$/.test(item.action)).length, 0, page + ' lists no dated rows')
  })
})

test('a day the phone has no history for is reported rather than drawn empty', () => {
  const older = Object.assign({}, snapshot, { history: null })
  const items = dashboard.rows(older, 'trend:exercise:week:' + snapshot.day, null)
  assert.equal(items.filter(item => item.bars).length, 0)
  assert.ok(items.some(item => item.title === '趋势数据待同步'))
  const measure = dashboard.rows(older, 'measure:weight:week:' + snapshot.day, null)
  assert.equal(measure.filter(item => item.bars).length, 0)
  assert.ok(measure.some(item => item.title === '趋势数据待同步'))
})
