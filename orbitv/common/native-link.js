export const LINK_KEY = 'vitality.native-link.v1'
export function validateLink(raw) {
  const value = typeof raw === 'string' ? JSON.parse(raw) : raw
  if (!value || value.schema !== 1 || value.appId !== 'com.lrh.healthvivo.watch' ||
      value.baseUrl !== 'http://127.0.0.1:28341' || !/^[0-9a-f]{64}$/.test(value.token || '')) {
    throw new Error('请粘贴活力记录 App 复制的完整配对信息')
  }
  return { schema: 1, appId: value.appId, baseUrl: value.baseUrl, token: value.token }
}
export async function nativeRequest(link, path, method = 'GET', body) {
  const controller = new AbortController(); const timeout = setTimeout(() => controller.abort(), 4000)
  try {
    const response = await fetch(link.baseUrl + path, { method, headers: { Authorization: 'Bearer ' + link.token, 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined, signal: controller.signal, cache: 'no-store' })
    if (!response.ok) {
      const error = new Error(response.status === 401 ? '配对信息已失效，请重新复制' : response.status === 404 && path === '/heartbeat' ? '请更新手机 App 至 0.12.3 或更高版本' : '手机接口暂不可用')
      error.status = response.status; throw error
    }
    return await response.json()
  } catch (error) {
    if (error.status) throw error
    throw new Error('手机 App 未连接，请打开活力记录并开启持续连接；此页会自动重试')
  } finally { clearTimeout(timeout) }
}
export function readControl(link, publish = false) { return nativeRequest(link, publish ? '/request' : '/control', publish ? 'POST' : 'GET') }
export function heartbeat(link, transportReady) { return nativeRequest(link, '/heartbeat', 'POST', { transportReady }) }
