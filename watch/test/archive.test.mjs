// The day archive and the one app-wide record beside it.
//
// The phone attaches the sparse series to the newest day's snapshot alone and sends an explicit null
// with every other day, so "the series" is not part of any day: the archive keeps the last one it was
// handed under its own key, hands it to every page whichever day is on screen, and has it again after
// a restart with no phone in the room. These tests run the archive over a storage stub, which is the
// same call interface the watch's storage module exposes (success/fail callbacks, one key at a time).

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createArchive } from '../src/lib/archive.mjs'

const SERIES_KEY = 'fitness.history.v3'
const ROOT_KEY = 'fitness.index.v3'
const LEGACY_KEY = 'fitness.dashboard.v2'
const KINDS = ['breakfast', 'lunch', 'dinner', 'snack']

/** A storage module with the watch's one-key-at-a-time callback interface. */
function storage() {
  const values = {}
  return {
    values: values,
    get(options) { options.success(values[options.key] === undefined ? options.default : values[options.key]) },
    set(options) { values[options.key] = options.value; options.success({}) },
    delete(options) { delete values[options.key]; options.success({}) }
  }
}

/** A day's snapshot: `history` is null on every day the phone does not consider its today. */
function snapshot(day, history, revision) {
  return {
    schema: 3, client: 'com.lrh.healthvivo.watch', day: day, today: day, revision: revision || 1,
    generatedAt: 1760400000000, steps: 8000, workoutSteps: 0, recordedSteps: 8000, weightKg: 70, targetWeightKg: 65,
    energy: { intakeKcal: 1, restingKcal: 1600, activityKcal: 1, referenceKcal: 1900, pendingCount: 0, overlapHandled: true },
    meals: KINDS.map(kind => ({ kind: kind, count: 0, omitted: 0, kcal: null, foods: [] })),
    exerciseCount: 0, omittedExercises: 0, exercises: [], advice: null,
    history: history === undefined ? null : history
  }
}

function series(days, weights, fats) {
  return { days: (days || []).map(day => ({ day: day, intakeKcal: 1200, activityKcal: 200, referenceKcal: 1800 })),
    weights: (weights || []).map(entry => ({ day: entry[0], value: entry[1] })),
    fats: (fats || []).map(entry => ({ day: entry[0], value: entry[1] })) }
}

test('the series sent with the newest day is kept and survives a restart', async () => {
  const device = storage()
  const archive = createArchive(device)
  await archive.load()
  assert.equal(archive.history(), null, 'nothing received yet')
  const newest = series(['2026-10-12', '2026-10-13'], [['2026-10-13', 70]], [['2026-10-12', 21.5]])
  await archive.merge([snapshot('2026-10-14', newest)])
  assert.deepEqual(archive.history(), newest)
  // It is its own record rather than part of a day: one key beside the index.
  assert.deepEqual(Object.keys(device.values).sort(), [ROOT_KEY, 'fitness.day.v3.2026-10-14.1', SERIES_KEY].sort())
  // A later run of the watch, with no phone anywhere near it, still has the charts.
  const restarted = createArchive(device)
  await restarted.load()
  assert.deepEqual(restarted.history(), newest)
  assert.deepEqual(restarted.dates(), ['2026-10-14'])
  assert.deepEqual(restarted.versions(), { '2026-10-14': 1 })
})

test('a day that carries no series never clears the one the watch holds', async () => {
  const device = storage()
  const archive = createArchive(device)
  await archive.load()
  const newest = series(['2026-10-14'])
  await archive.merge([snapshot('2026-10-14', newest)])
  // Every other day the phone sends carries an explicit null, including ones that arrive afterwards.
  assert.equal(snapshot('2026-10-12').history, null)
  await archive.merge([snapshot('2026-10-12')])
  assert.deepEqual(archive.history(), newest)
  const restarted = createArchive(device)
  await restarted.load()
  assert.deepEqual(restarted.history(), newest)
  // A day that is re-sent unchanged still hands over the series it is carrying.
  await archive.merge([snapshot('2026-10-14', newest)])
  assert.deepEqual(archive.history(), newest)
})

test('an older day carrying a series cannot replace a newer one', async () => {
  const device = storage()
  const archive = createArchive(device)
  await archive.load()
  const newer = series(['2026-10-14'], [['2026-10-14', 70]])
  const older = series(['2026-10-12'])
  await archive.merge([snapshot('2026-10-14', newer)])
  // A phone that still attached the series to every day must not walk the charts backwards.
  await archive.merge([snapshot('2026-10-12', older)])
  assert.deepEqual(archive.history(), newer)
  // The newest day's copy does replace it, in either arrival order.
  const latest = series(['2026-10-15'], [['2026-10-15', 69.5]])
  await archive.merge([snapshot('2026-10-15', latest)])
  assert.deepEqual(archive.history(), latest)
  assert.deepEqual(archive.history().weights, [{ day: '2026-10-15', value: 69.5 }])
})

test('a damaged series cache is dropped without taking the day archive with it', async () => {
  const device = storage()
  const archive = createArchive(device)
  await archive.load()
  const held = series(['2026-10-14'], [['2026-10-14', 70]])
  await archive.merge([snapshot('2026-10-14', held)])
  device.values[SERIES_KEY] = '{ not json'
  const restarted = createArchive(device)
  await restarted.load()
  assert.equal(restarted.history(), null, 'the series is a cache, so a damaged one is simply absent')
  assert.deepEqual(restarted.dates(), ['2026-10-14'], 'the days are untouched')
  // A day cached before the series became app-wide still carries its own copy, and reading it hands
  // that copy back: an install that has not synced since the phone changed keeps its charts.
  assert.equal((await restarted.read('2026-10-14')).day, '2026-10-14')
  assert.deepEqual(restarted.history(), held)
  // The next sync writes it under its own key again.
  await restarted.merge([snapshot('2026-10-15', series(['2026-10-15']))])
  assert.notEqual(device.values[SERIES_KEY], '{ not json')
  const third = createArchive(device)
  await third.load()
  assert.deepEqual(third.history(), series(['2026-10-15']))
})

test('the old single-day cache is still readable and hands over its series', async () => {
  const device = storage()
  const held = series(['2026-10-05', '2026-10-12'], [['2026-10-05', 71]])
  device.values[LEGACY_KEY] = JSON.stringify({ snapshot: snapshot('2026-10-12', held), savedAt: 1760400000000 })
  const archive = createArchive(device)
  await archive.load()
  assert.deepEqual(archive.dates(), ['2026-10-12'])
  assert.deepEqual(archive.history(), held)
  assert.equal(archive.savedAt('2026-10-12'), 1760400000000)
  assert.equal((await archive.read('2026-10-12')).weightKg, 70)
})
