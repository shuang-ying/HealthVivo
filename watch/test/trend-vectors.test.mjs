// The one piece of the energy model implemented twice: the phone aggregates its history into week,
// month and year columns in Kotlin (Trends.kt), the watch aggregates the same sparse history in
// JavaScript. Neither side can fail to compile because of the other, so the two only stay honest if
// something compares their answers directly.
//
// `watch/test/fixtures/trend-vectors.json` is written by the phone's own TrendVectorsTest from the
// columns Trends.energy/Trends.measure actually produce. This file runs the watch's aggregation over
// the fixture's own days/weights/fats and demands the whole object, field by field: a label, a null
// reference or a future flag that drifts apart turns this red instead of quietly drawing a chart that
// disagrees with the phone.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import dashboard from '../src/lib/dashboard.mjs'

const fixture = JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'fixtures', 'trend-vectors.json'), 'utf8'))

const BUCKET_KEYS = ['activityKcal', 'future', 'intakeKcal', 'label', 'rangeText', 'recorded', 'referenceKcal', 'shortText', 'startDay']
const POINT_KEYS = ['future', 'label', 'rangeText', 'shortText', 'startDay', 'value']

test('the vector fixture covers every scale on both of the phone test anchors', () => {
  assert.ok(fixture.cases.length >= 6, 'expected a week, month and year case for each anchor')
  const seen = {}
  fixture.cases.forEach(item => { seen[item.scale] = true })
  assert.deepEqual(Object.keys(seen).sort(), ['month', 'week', 'year'])
  assert.ok(fixture.days.length > 0 && fixture.weights.length > 0 && fixture.fats.length > 0)
  assert.match(fixture.today, /^\d{4}-\d{2}-\d{2}$/)
})

// The whole object, not a summary: label, shortText, rangeText, referenceKcal nulls and future are all
// part of the answer, and deepEqual over the exact key set also fails on a field the watch invented.
test('the watch aggregation reproduces every phone trend vector', () => {
  fixture.cases.forEach(item => {
    const where = item.scale + ' anchored on ' + item.anchor
    const period = dashboard.period(item.anchor, item.scale)
    assert.equal(period.start, item.start, where + ': period start')
    assert.equal(period.endExclusive, item.endExclusive, where + ': period endExclusive')
    assert.equal(period.endInclusive, item.endInclusive, where + ': period endInclusive')
    assert.equal(period.label, item.label, where + ': period label')
    const buckets = dashboard.energy(fixture.days, period, fixture.today)
    const weights = dashboard.measure(fixture.weights, period, fixture.today)
    const fats = dashboard.measure(fixture.fats, period, fixture.today)
    assert.deepEqual(buckets, item.buckets, where + ': energy columns')
    assert.deepEqual(weights, item.weights, where + ': weight points')
    assert.deepEqual(fats, item.fats, where + ': body-fat points')
    assert.equal(buckets.length, item.buckets.length, where + ': column count')
    buckets.forEach((bucket, index) => assert.deepEqual(Object.keys(bucket).sort(), BUCKET_KEYS, where + ': bucket ' + index + ' fields'))
    weights.forEach((point, index) => assert.deepEqual(Object.keys(point).sort(), POINT_KEYS, where + ': weight point ' + index + ' fields'))
    fats.forEach((point, index) => assert.deepEqual(Object.keys(point).sort(), POINT_KEYS, where + ': body-fat point ' + index + ' fields'))
  })
})

// A column that is one day wide and a column that is a whole month must both name the calendar step
// they cover, or the two languages can agree on the numbers and still label them differently.
test('the vector labels follow the scale the phone labels them with', () => {
  fixture.cases.forEach(item => {
    const period = dashboard.period(item.anchor, item.scale)
    const buckets = dashboard.energy(fixture.days, period, fixture.today)
    if (item.scale === 'week') {
      assert.deepEqual(buckets.map(bucket => bucket.label), ['一', '二', '三', '四', '五', '六', '日'])
      assert.equal(buckets.length, 7)
      buckets.forEach(bucket => assert.match(bucket.rangeText, /^\d{1,2}\/\d{1,2} 周[一二三四五六日]$/))
    } else if (item.scale === 'month') {
      assert.equal(buckets.length, 30 + (item.anchor === '2026-10-14' ? 1 : 0))
      assert.equal(buckets[0].label, '1')
      assert.equal(buckets[buckets.length - 1].label, String(buckets.length))
      buckets.forEach(bucket => assert.match(bucket.rangeText, /^\d{1,2}\/\d{1,2}$/))
    } else {
      assert.equal(buckets.length, 12)
      assert.deepEqual(buckets.map(bucket => bucket.label), ['1月', '2月', '3月', '4月', '5月', '6月', '7月', '8月', '9月', '10月', '11月', '12月'])
      buckets.forEach((bucket, index) => assert.equal(bucket.rangeText, item.anchor.slice(0, 4) + '年' + (index + 1) + '月'))
    }
  })
})
