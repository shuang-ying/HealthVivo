import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import dashboard from '../src/lib/dashboard.mjs'

// The phone writes this file in WatchSnapshotContractTest, so it is the phone's real output rather
// than a hand-written copy of what we believe the phone sends. If a field is renamed or retyped on
// either side, this test or the Kotlin one fails instead of the watch quietly dropping a day.
const snapshot = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'snapshot-v1.json'), 'utf8'))

test('the snapshot the phone produces passes the watch validator', () => {
  assert.equal(dashboard.validate(snapshot), snapshot)
})

test('the daily steps row shows both the total and the split the phone computed', () => {
  const row = dashboard.rows(snapshot, 'home').filter(item => item.title === '每日步数')[0]
  assert.equal(row.body, '10000 步\n运动 3000 + 记录 7000')
})

test('the phone step deduction and the watch reading of it agree on the energy figures', () => {
  const energy = dashboard.rows(snapshot, 'energy')
  const structure = energy.filter(item => item.title === '消耗构成')[0]
  // 1648.75 kcal of resting metabolism, and 171.5 kcal of walking over the remaining 7000 steps plus
  // 441.875 from the two workouts. The watch rounds these to whole kilocalories.
  assert.match(structure.body, /静息 1649 千卡/)
  assert.match(structure.body, /已记录净活动 613 千卡/)
})

test('every trend tab renders one bar per day of the phone series', () => {
  for (const page of ['trend:meals', 'trend:exercise', 'trend:balance']) {
    const items = dashboard.rows(snapshot, page)
    const chart = items.filter(item => item.bars && item.bars.length)[0]
    assert.ok(chart, page + ' rendered no bars')
    assert.equal(chart.bars.length, snapshot.trend.length)
    chart.bars.forEach(bar => {
      assert.ok(bar.height >= 3 && bar.height <= 120)
      assert.match(bar.color, /^#[0-9A-Fa-f]{6}$/)
      assert.match(bar.label, /^\d{2}$/)
    })
  }
})

test('the trend tabs carry the same action the page list accepts', () => {
  const items = dashboard.rows(snapshot, 'trend:meals')
  const tabs = items.filter(item => item.tab)
  assert.equal(tabs.length, 3)
  assert.deepEqual(tabs.map(item => item.action), ['trend:meals', 'trend:exercise', 'trend:balance'])
  assert.equal(tabs.filter(item => item.active).length, 1)
  assert.equal(tabs.filter(item => item.active)[0].action, 'trend:meals')
})

test('a day the phone has no trend for is reported rather than drawn empty', () => {
  const older = Object.assign({}, snapshot, { trend: undefined })
  const items = dashboard.rows(older, 'trend:exercise')
  assert.equal(items.filter(item => item.bars).length, 0)
  assert.ok(items.some(item => item.title === '趋势数据待同步'))
})
