const CLIENT = 'com.lrh.healthvivo.watch'
const TREND_PLOT = 120
const TREND_WIDTH = 360
const TREND_TABS = [
  { page: 'trend:meals', title: '饮食', label: '每日摄入', color: '#7955D8' },
  { page: 'trend:exercise', title: '运动', label: '每日活动', color: '#42AC81' },
  { page: 'trend:balance', title: '热量剩余', label: '每日热量剩余', color: '#19633F' }
]
const TREND_MISSING = '#3A3448'
const TREND_ZERO = '#51485E'
const TREND_NEGATIVE = '#913643'
function integer(value) { return typeof value === 'number' && isFinite(value) && Math.floor(value) === value }
function optionalNumber(value) { return value === null || (typeof value === 'number' && isFinite(value) && value >= 0) }
// Absent and JSON null both mean "the phone could not derive this count" (it sends `recordedSteps: null`
// on any day without recorded steps), so a null is accepted exactly like a missing key.
function optionalInteger(value) { return value === undefined || value === null || (integer(value) && value >= 0 && value <= 200000) }
function trendTab(page) {
  for (let index = 0; index < TREND_TABS.length; index++) if (TREND_TABS[index].page === page) return TREND_TABS[index]
  return TREND_TABS[0]
}
function trendValue(page, point) {
  if (page === 'trend:exercise') return point.activityKcal
  if (page === 'trend:balance') return point.referenceKcal === null ? null : point.referenceKcal - point.intakeKcal
  return point.intakeKcal
}
function trendHeight(value, peak) {
  if (value === null || !peak) return 3
  return Math.max(3, Math.round(Math.abs(value) / peak * TREND_PLOT))
}
function trendColor(page, value) {
  if (value === null) return TREND_MISSING
  if (!value) return TREND_ZERO
  if (page === 'trend:balance') return value < 0 ? TREND_NEGATIVE : trendTab(page).color
  return trendTab(page).color
}
function trendAmount(page, value) {
  if (value === null) return '待补全'
  if (page !== 'trend:balance') return kcal(value)
  return (value >= 0 ? '+' : '−') + Math.round(Math.abs(value)) + ' 千卡'
}
function trendTotals(values) {
  let sum = 0; let count = 0
  values.forEach(value => { if (value !== null) { sum += value; count++ } })
  return { sum: count ? sum : null, count: count }
}
function validateTrend(trend, day) {
  if (!Array.isArray(trend) || trend.length > 14) throw new Error('trend')
  let previous = ''
  trend.forEach(entry => {
    if (!entry || typeof entry.day !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(entry.day) || entry.day <= previous ||
        !optionalNumber(entry.intakeKcal) || entry.intakeKcal === null ||
        !optionalNumber(entry.activityKcal) || entry.activityKcal === null || !optionalNumber(entry.referenceKcal)) throw new Error('trend')
    previous = entry.day
  })
  if (trend.length && previous !== day) throw new Error('trend day')
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
  if (!value || value.schema !== 2 || value.client !== CLIENT || !/^\d{4}-\d{2}-\d{2}$/.test(value.day) ||
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
        !(body.fatDay === null || (typeof body.fatDay === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(body.fatDay))) ||
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
  if (value.trend !== undefined) validateTrend(value.trend, value.day)
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
function time(value) {
  const date = new Date(value)
  return (date.getMonth() + 1) + '/' + date.getDate() + ' ' + date.getHours() + ':' + ('0' + date.getMinutes()).slice(-2)
}
function rows(snapshot, page) {
  const result = []
  function row(title, body, action, highlight) { const item = { id: String(result.length), title, body, action: action || '', highlight: !!highlight, cardClass: 'card', hint: action ? '点击查看 ›' : '' }; result.push(item); return item }
  function paragraphs(title, text) { const chars = Array.from(text); for (let start = 0; start < chars.length; start += 90) row(start ? title + ' · 续' : title, chars.slice(start, start + 90).join('')) }
  if (!snapshot) { row('这一天暂无已同步记录', '可在手机录入后点击更新，或在此点击获取更新。'); return result }
  function energy(action) {
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
  function trend(page) {
    const points = Array.isArray(snapshot.trend) ? snapshot.trend : []
    const tab = trendTab(page)
    TREND_TABS.forEach(item => {
      const strip = row(item.title, item.page === page ? '当前显示' : '点击切换', item.page)
      strip.tab = true; strip.active = item.page === page; strip.cardClass = item.page === page ? 'tab-on' : 'tab-off'; strip.hint = ''
    })
    if (!points.length) { row('趋势数据待同步', '请在手机点击“更新到手表”后查看'); return }
    const values = points.map(point => trendValue(page, point))
    const totals = trendTotals(values)
    let peak = 0
    values.forEach(value => { if (value !== null && Math.abs(value) > peak) peak = Math.abs(value) })
    const width = Math.max(6, Math.min(40, Math.floor(TREND_WIDTH / points.length) - 4))
    const chart = row(tab.label, trendAmount(page, totals.sum), '', true)
    chart.bars = points.map((point, index) => ({ width: width, height: trendHeight(values[index], peak),
      color: trendColor(page, values[index]), label: point.day.slice(8) }))
    row('近 ' + points.length + ' 天', '日均 ' + trendAmount(page, totals.count ? totals.sum / totals.count : null))
  }
  if (page === 'home') {
    stepsRow()
    energy('energy')
    row('趋势', '饮食 · 运动 · 热量剩余', 'trend:meals')
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
    energy('')
    row('消耗构成', '静息 ' + kcal(snapshot.energy.restingKcal) + '\n已记录净活动 ' + kcal(snapshot.energy.activityKcal))
    row('参考口径', '当天消耗参考 − 当天登记摄入。消耗参考为静息估算加已记录净活动。')
  } else if (page === 'exercise') {
    snapshot.exercises.forEach(item => {
      const parts = []
      if (item.minutes !== null) parts.push(number(item.minutes, ' 分钟'))
      if (item.distanceKm !== null) parts.push(number(item.distanceKm, ' km'))
      if (item.sets !== null) parts.push(item.sets + ' 组' + (item.reps !== null ? ' × ' + item.reps + ' 次' : ''))
      if (item.loadKg !== null) parts.push(number(item.loadKg, ' kg'))
      parts.push(item.kcal === null ? '待估算' : kcal(item.kcal))
      row(item.name, ({ aerobic: '有氧', anaerobic: '无氧', unspecified: '未分类' })[item.category] + '\n' + parts.join(' · '))
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
  } else if (page.indexOf('trend:') === 0) {
    trend(page)
  } else if (page === 'advice') {
    if (!snapshot.advice) row('尚未生成建议', '可在手机生成后手动更新')
    else { paragraphs('吃饭建议', snapshot.advice.content.food); paragraphs('锻炼建议', snapshot.advice.content.exercise) }
  }
  return result
}
export default { CLIENT: CLIENT, checksum: checksum, validate: validate, assembler: assembler, rows: rows, time: time }
