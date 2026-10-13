const previewStorage = new Map()
let previewRuntimeInfo = {
  apiVersion: 2,
  appId: 'browser.preview',
  name: '浏览器预览',
  capabilities: [],
}
let previewOrbitV = null

function unsupported(name) {
  const error = new Error(`${name} 需要在 OrbitV 中运行`)
  error.code = 'ORBITV_REQUIRED'
  throw error
}

function createPreviewOrbitV() {
  const fs = new Proxy({}, {
    get: (_, name) => () => unsupported(`ov.fs.${String(name)}`),
  })
  const server = new Proxy({}, {
    get: (_, name) => {
      if (name === 'connections') return () => []
      return () => unsupported(`ov.server.${String(name)}`)
    },
  })

  return {
    version: 'browser-preview',
    runtime: {
      getInfo: async () => ({ ...previewRuntimeInfo }),
    },
    watch: {
      getState: async () => ({
        connected: false,
        transportReady: false,
        watchSn: '',
      }),
      getInfo: async () => ({
        connected: false,
        transportReady: false,
        name: null,
        productId: null,
        serialNumber: null,
        macAddress: null,
        bleAddress: null,
        bleMacAddress: null,
        classicMacAddress: null,
        battery: {
          level: null,
          state: 'unknown',
          charging: null,
          rawState: null,
        },
        storage: {
          totalBytes: null,
          freeBytes: null,
          usedBytes: null,
        },
      }),
    },
    storage: {
      get: async (key) => previewStorage.get(key) ?? null,
      set: async (key, value) => {
        previewStorage.set(key, value)
        return value
      },
      has: async (key) => previewStorage.has(key),
      remove: async (key) => previewStorage.delete(key),
      keys: async () => [...previewStorage.keys()],
      clear: async () => {
        previewStorage.clear()
        return true
      },
    },
    ui: {
      toast: async (options) => {
        const value = typeof options === 'string' ? { message: options } : options
        console.info('[OrbitV Toast]', value?.title || '', value?.message || '')
        return { shown: true }
      },
    },
    fs,
    file: {
      list: async () => ({ files: [] }),
      choose: () => unsupported('ov.file.choose'),
      remove: () => unsupported('ov.file.remove'),
      export: () => unsupported('ov.file.export'),
    },
    server,
    navigation: {
      setPage: async (options = {}) => ({
        title: String(options.title || ''),
        navigationStyle: options.navigationStyle || 'default',
        backgroundColor: options.backgroundColor || '',
        navigationBarBackgroundColor: options.navigationBarBackgroundColor || '',
        navigationBarTextStyle: options.navigationBarTextStyle || 'auto',
      }),
      onBack: () => ({ remove() {} }),
      back: async () => {
        history.back()
        return true
      },
      close: async () => false,
      reload: () => location.reload(),
    },
  }
}

let readyPromise = null
let initializationPromise = null

export const isBrowserPreview = import.meta.env.DEV && !window.ov

export function getOrbitV() {
  if (window.ov) return Promise.resolve(window.ov)
  if (isBrowserPreview) {
    previewOrbitV ||= createPreviewOrbitV()
    return Promise.resolve(previewOrbitV)
  }
  if (!readyPromise) {
    readyPromise = new Promise((resolve) => {
      window.addEventListener('ovready', () => resolve(window.ov), { once: true })
    })
  }
  return readyPromise
}

export function initializeOrbitV(config = {}) {
  if (initializationPromise) return initializationPromise

  const expectedAppId = String(config.appId || '').trim()
  if (!expectedAppId) {
    return Promise.reject(new Error('orbitv.json 缺少 appId'))
  }

  if (isBrowserPreview && !previewOrbitV) {
    previewRuntimeInfo = {
      ...previewRuntimeInfo,
      appId: expectedAppId,
      name: String(config.name || '浏览器预览'),
    }
  }

  initializationPromise = getOrbitV().then(async (ov) => {
    const runtime = await ov.runtime.getInfo()
    if (runtime.appId !== expectedAppId) {
      const error = new Error(
        `扩展程序包 appId 不一致：配置为 ${expectedAppId}，运行时为 ${runtime.appId}`,
      )
      error.code = 'APP_ID_MISMATCH'
      throw error
    }
    return Object.freeze({ ov, runtime })
  })
  return initializationPromise
}

export async function runWithOrbitV(task) {
  return task(await getOrbitV())
}
