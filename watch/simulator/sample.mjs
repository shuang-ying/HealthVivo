// Synthetic data only. This module is outside src and is never packaged in the production RPK.
function sample() {
  const now = new Date()
  const day = now.getFullYear() + '-' + ('0' + (now.getMonth() + 1)).slice(-2) + '-' + ('0' + now.getDate()).slice(-2)
  const kinds = ['breakfast', 'lunch', 'dinner', 'snack']
  const foodRows = [
    [{ name: '燕麦粥', grams: 220, kcal: 180, estimated: false }, { name: '鸡蛋', grams: 50, kcal: 72, estimated: false }],
    [{ name: '熟米饭', grams: 150, kcal: 195, estimated: false }, { name: '鸡胸肉与青菜', grams: 250, kcal: 320, estimated: true }],
    [{ name: '杂粮饭', grams: 150, kcal: 180, estimated: false }, { name: '番茄豆腐', grams: 200, kcal: 170, estimated: true }], []
  ]
  return { schema: 2, client: 'com.lrh.healthvivo.watch', day: day, revision: Date.now(), generatedAt: Date.now(),
    steps: 6800, weightKg: 70.5, targetWeightKg: 65,
    body: { bmi: 23, bmiStatus: '正常', fatPercent: 19.5, fatDay: day, fatStatus: '参考正常',
      bmiScale: { min: 12, max: 36, breaks: [18.5, 24, 28], labels: ['偏低', '正常', '超重', '肥胖'], colors: ['#E9B748', '#42AC81', '#E9B748', '#E16B73'], unit: '', value: 23 },
      fatScale: { min: 0, max: 50, breaks: [8, 20], labels: ['偏低', '正常', '偏高'], colors: ['#E9B748', '#42AC81', '#E16B73'], unit: '%', value: 19.5 } },
    energy: { intakeKcal: 1117, restingKcal: 1610, activityKcal: 286, referenceKcal: 1896, pendingCount: 1, overlapHandled: true },
    meals: kinds.map((kind, index) => ({ kind: kind, count: foodRows[index].length, omitted: 0,
      kcal: foodRows[index].length ? foodRows[index].reduce((sum, food) => sum + food.kcal, 0) : null, foods: foodRows[index] })),
    exerciseCount: 2, omittedExercises: 0,
    exercises: [
      { name: '轻松跑步', category: 'aerobic', minutes: 30, distanceKm: 3, sets: null, reps: null, loadKg: null,
        kcal: 211.5, net: true, estimated: false, includedInSteps: true },
      { name: '哑铃深蹲', category: 'anaerobic', minutes: null, distanceKm: null, sets: 4, reps: 12, loadKg: 10,
        kcal: null, net: false, estimated: false, includedInSteps: false }
    ],
    advice: { generatedAt: Date.now(), stale: false, content: {
      overview: '这是模拟器的演示建议与虚构记录，不代表用户真实饮食或运动。',
      energy: '已记录摄入 1117 千卡。静息和已知活动只构成部分消耗参考，请结合完整记录及连续体重趋势判断调整方向。',
      food: '三餐已有记录，建议核对烹调油、酱料与实际份量，并记录未填写的加餐。',
      exercise: '跑步已标记计入步数，避免重复相加。力量训练仅有组数，补充总时长、负重和休息说明后再估算。',
      missing: ['核对烹调油与酱料', '补充力量训练总时长']
    } }
  }
}
export default sample
