import fetch from '@blueos.network.fetch'
import request from '@blueos.network.request'
import file from '@blueos.storage.file'

const DEFAULT_BASE_URL = 'http://127.0.0.1:23101/bridge/v1'
const DEFAULT_DOWNLOAD_DIRECTORY = 'internal://mass/orbitv/downloads'

function createError(code, message, details) {
  const error = new Error(message || '请求 OrbitV 失败')
  error.code = code || 'ORBITV_ERROR'
  error.details = details
  return error
}

function decodeJson(value) {
  if (typeof value !== 'string') return value
  try {
    return JSON.parse(value)
  } catch (_) {
    return value
  }
}

function parseEnvelope(response) {
  const body = decodeJson(response.data)
  if (response.code < 200 || response.code >= 300 || !body || body.ok !== true) {
    const detail = body && body.error ? body.error : {}
    throw createError(detail.code || 'HTTP_' + response.code, detail.message, body)
  }
  return body.data || {}
}

function responseError(response, fallback) {
  const body = decodeJson(response.data)
  if (response.code === 409) {
    return createError(
      'APP_PAGE_NOT_OPEN',
      '请在手机 OrbitV 中打开对应扩展程序',
      body
    )
  }
  const detail = body && body.error ? body.error : {}
  return createError(detail.code || 'HTTP_' + response.code, detail.message || fallback, body)
}

function wait(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds))
}

function requestJson(options, remainingRetries) {
  return new Promise((resolve, reject) => {
    fetch.fetch({
      url: options.url,
      method: options.method || 'GET',
      data: options.data,
      header: options.header,
      responseType: 'json',
      timeout: options.timeout || 8000,
      success(response) {
        try {
          resolve(parseEnvelope(response))
        } catch (error) {
          reject(error)
        }
      },
      async fail(data, code) {
        if (remainingRetries > 0) {
          await wait(350)
          try {
            resolve(await requestJson(options, remainingRetries - 1))
          } catch (error) {
            reject(error)
          }
          return
        }
        reject(createError('NETWORK_' + code, '无法连接手机 OrbitV', data))
      },
    })
  })
}

function requestRaw(options, remainingRetries) {
  return new Promise((resolve, reject) => {
    fetch.fetch({
      url: options.url,
      method: options.method || 'GET',
      data: options.data,
      header: options.header,
      responseType: options.responseType || 'json',
      timeout: options.timeout || 15000,
      success(response) {
        if (response.code >= 200 && response.code < 300) {
          resolve({
            status: response.code,
            data: decodeJson(response.data),
            headers: response.headers || {},
          })
          return
        }
        reject(responseError(response, '开发者接口请求失败'))
      },
      async fail(data, code) {
        if (remainingRetries > 0) {
          await wait(350)
          try {
            resolve(await requestRaw(options, remainingRetries - 1))
          } catch (error) {
            reject(error)
          }
          return
        }
        reject(createError('NETWORK_' + code, '无法连接手机端开发者接口', data))
      },
    })
  })
}

function ensureDirectory(uri) {
  return new Promise((resolve, reject) => {
    file.access({
      uri,
      success() {
        resolve(uri)
      },
      fail() {
        file.mkdir({
          uri,
          recursive: true,
          success() {
            resolve(uri)
          },
          fail(data, code) {
            reject(createError('FILE_' + code, '无法创建手表保存目录', data))
          },
        })
      },
    })
  })
}

function writeText(uri, text) {
  return new Promise((resolve, reject) => {
    file.writeText({
      uri,
      text,
      success() {
        resolve(uri)
      },
      fail(data, code) {
        reject(createError('FILE_' + code, '无法创建手表测试文件', data))
      },
    })
  })
}

function downloadTo(url, destinationUri, description) {
  return new Promise((resolve, reject) => {
    request.download({
      url,
      filename: destinationUri,
      description,
      success(result) {
        request.onDownloadComplete({
          token: result.token,
          success(data) {
            resolve(data.uri || destinationUri)
          },
          fail(data, code) {
            reject(createError('DOWNLOAD_' + code, '文件下载失败', data))
          },
        })
      },
      fail(data, code) {
        reject(createError('DOWNLOAD_' + code, '无法创建下载任务', data))
      },
    })
  })
}

function uploadTo(url, localUri, options, envelope) {
  return new Promise((resolve, reject) => {
    const config = options || {}
    request.upload({
      url,
      method: config.method || 'POST',
      header: config.header,
      files: [{
        uri: localUri,
        name: config.fieldName || 'file',
        filename: config.filename,
        type: config.contentType,
      }],
      data: config.data,
      success(response) {
        if (response.code < 200 || response.code >= 300) {
          reject(responseError(response, '文件上传失败'))
          return
        }
        try {
          const body = decodeJson(response.data)
          resolve(envelope ? parseEnvelope({ ...response, data: body }) : body)
        } catch (error) {
          reject(error)
        }
      },
      fail(data, code) {
        reject(createError('UPLOAD_' + code, '文件上传到手机失败', data))
      },
    })
  })
}

function basename(value) {
  const parts = String(value || '').split('/')
  return parts[parts.length - 1] || 'download.bin'
}

function appRoute(appBaseUrl, route) {
  if (!route || route[0] !== '/') {
    throw createError('INVALID_ROUTE', '开发者接口路径必须以 / 开头')
  }
  return appBaseUrl + route
}

export function createOrbitVClient(config) {
  if (!config || !config.appId) {
    throw createError('APP_ID_REQUIRED', '创建 OrbitV 客户端时必须传入 appId')
  }
  const appId = config.appId
  const baseUrl = config.baseUrl || DEFAULT_BASE_URL
  const encodedAppId = encodeURIComponent(appId)
  const appBaseUrl = baseUrl + '/apps/' + encodedAppId
  const jsonHeaders = {
    'X-OrbitV-App-Id': appId,
    'Content-Type': 'application/json',
  }

  function dataUrl(key) {
    return baseUrl + '/data/' + encodeURIComponent(key)
  }

  function fsContentUrl(remotePath) {
    return baseUrl + '/fs/content?appId=' + encodedAppId
      + '&path=' + encodeURIComponent(remotePath)
  }

  return Object.freeze({
    data: Object.freeze({
      async get(key) {
        const result = await requestJson({
          url: dataUrl(key),
          header: { 'X-OrbitV-App-Id': appId },
        }, 1)
        return result.value
      },
      async set(key, value) {
        const result = await requestJson({
          url: dataUrl(key),
          method: 'PUT',
          header: jsonHeaders,
          data: JSON.stringify({ value }),
        }, 1)
        return result.value
      },
      async remove(key) {
        const result = await requestJson({
          url: dataUrl(key),
          method: 'DELETE',
          header: { 'X-OrbitV-App-Id': appId },
        }, 1)
        return result.removed === true
      },
    }),
    file: Object.freeze({
      // 返回用户在扩展程序页面选择的文件。
      // 每项包含 name、size、mimeType、createdAt，可直接传给 download。
      async list() {
        const result = await requestJson({
          url: baseUrl + '/files',
          header: { 'X-OrbitV-App-Id': appId },
        }, 1)
        return result.files || []
      },
      async download(record, options) {
        if (!record || !record.id) {
          throw createError('INVALID_FILE', '请传入 file.list() 返回的文件对象')
        }
        const directory = options && options.directory
          ? options.directory
          : DEFAULT_DOWNLOAD_DIRECTORY
        await ensureDirectory(directory)
        const destinationUri = directory + '/' + (options && options.filename
          ? options.filename
          : record.id + '-' + basename(record.name))
        const url = baseUrl + '/files/' + encodeURIComponent(record.id)
          + '/content?appId=' + encodedAppId
        return downloadTo(url, destinationUri, record.name || 'OrbitV 文件')
      },
    }),
    fs: Object.freeze({
      url(remotePath) {
        return fsContentUrl(remotePath)
      },
      async stat(remotePath) {
        const result = await requestJson({
          url: baseUrl + '/fs/stat?appId=' + encodedAppId
            + '&path=' + encodeURIComponent(remotePath || ''),
          header: { 'X-OrbitV-App-Id': appId },
        }, 1)
        return result.entry || null
      },
      async list(remotePath) {
        const result = await requestJson({
          url: baseUrl + '/fs/list?appId=' + encodedAppId
            + '&path=' + encodeURIComponent(remotePath || ''),
          header: { 'X-OrbitV-App-Id': appId },
        }, 1)
        return result.entries || []
      },
      async download(remotePath, destinationUri, options) {
        if (!remotePath) throw createError('PATH_REQUIRED', '缺少手机端文件路径')
        const target = destinationUri
          || DEFAULT_DOWNLOAD_DIRECTORY + '/' + basename(remotePath)
        const slash = target.lastIndexOf('/')
        if (slash > 0) await ensureDirectory(target.slice(0, slash))
        return downloadTo(
          fsContentUrl(remotePath),
          target,
          options && options.description ? options.description : basename(remotePath)
        )
      },
      async upload(localUri, remotePath, options) {
        if (!localUri) throw createError('LOCAL_URI_REQUIRED', '缺少手表端文件 URI')
        if (!remotePath) throw createError('PATH_REQUIRED', '缺少手机端保存路径')
        const url = baseUrl + '/fs/upload?appId=' + encodedAppId
          + '&path=' + encodeURIComponent(remotePath)
        return uploadTo(url, localUri, options, true)
      },
    }),
    http: Object.freeze({
      request(route, options) {
        const config = options || {}
        return requestRaw({
          url: appRoute(appBaseUrl, route),
          method: config.method,
          data: config.data,
          header: config.header,
          responseType: config.responseType,
          timeout: config.timeout,
        }, config.retries === undefined ? 1 : config.retries)
      },
      async download(route, destinationUri, options) {
        const config = options || {}
        const slash = destinationUri.lastIndexOf('/')
        if (slash > 0) await ensureDirectory(destinationUri.slice(0, slash))
        return downloadTo(
          appRoute(appBaseUrl, route),
          destinationUri,
          config.description || basename(destinationUri)
        )
      },
      upload(route, localUri, options) {
        if (!localUri) throw createError('LOCAL_URI_REQUIRED', '缺少手表端文件 URI')
        return uploadTo(appRoute(appBaseUrl, route), localUri, options, false)
      },
    }),
    watchFile: Object.freeze({
      ensureDirectory,
      writeText,
    }),
  })
}
