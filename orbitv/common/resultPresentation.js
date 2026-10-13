export function readableValue(value) {
  if (value === null || value === undefined || value === '') return '无'
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) {
    if (!value.length) return '没有内容'
    return value.map((item) => readableValue(item)).join('、')
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value)
    if (!entries.length) return '没有内容'
    return entries
      .map(([key, item]) => `${key}：${readableValue(item)}`)
      .join('；')
  }
  return String(value)
}

export function rowsFromValue(value, labels = {}) {
  if (Array.isArray(value)) {
    return value.map((item, index) => ({
      key: `item-${index}-${readableValue(item)}`,
      label: `第 ${index + 1} 项`,
      value: readableValue(item),
      multiline: typeof item === 'object' && item !== null,
    }))
  }

  if (value && typeof value === 'object') {
    return Object.entries(value).map(([key, item]) => ({
      key,
      label: labels[key] || key,
      value: readableValue(item),
      multiline: typeof item === 'object' && item !== null,
    }))
  }

  return value === undefined
    ? []
    : [{ key: 'value', label: '内容', value: readableValue(value), multiline: true }]
}

export function failedResult(result, fallback = '请稍后重试') {
  return {
    title: '操作失败',
    message: result && result.message ? result.message : fallback,
    rows: [],
  }
}
