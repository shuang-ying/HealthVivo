import { createRouter, createWebHashHistory } from 'vue-router'
import { getOrbitV } from './common/orbitv.js'
import pageConfig from './pages.json'

const pageModules = import.meta.glob('./pages/**/*.vue')

const routes = pageConfig.pages.map((page, index) => {
  const componentPath = `./${page.path}.vue`
  const component = pageModules[componentPath]
  if (!component) throw new Error(`pages.json 找不到页面文件：${componentPath}`)
  const path = index === 0 ? '/' : `/${page.path}`
  const backgroundColor =
    page.style?.backgroundColor
    || pageConfig.globalStyle?.backgroundColor
    || ''
  const navigationBarBackgroundColor =
    page.style?.navigationBarBackgroundColor
    || pageConfig.globalStyle?.navigationBarBackgroundColor
    || backgroundColor

  const route = {
    path,
    name: page.path,
    component,
    meta: {
      title: page.style?.navigationBarTitleText || '',
      description: page.style?.description || '',
      group: page.style?.group || '',
      pageDepth: path.split('/').filter(Boolean).length,
      navigationStyle:
        page.style?.navigationStyle
        || pageConfig.globalStyle?.navigationStyle
        || 'default',
      backgroundColor,
      navigationBarBackgroundColor,
      navigationBarTextStyle:
        page.style?.navigationBarTextStyle
        || pageConfig.globalStyle?.navigationBarTextStyle
        || 'auto',
    },
  }
  if (index === 0) route.alias = `/${page.path}`
  return route
})

const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

let historyPosition = Number(window.history.state?.position ?? 0)

router.beforeEach((to, from) => {
  const toDepth = Number(to.meta.pageDepth || 0)
  const fromDepth = Number(from.meta.pageDepth || 0)
  const nextHistoryPosition = Number(window.history.state?.position)
  const isHistoryBack = Number.isFinite(nextHistoryPosition)
    && nextHistoryPosition < historyPosition
  to.meta.pageTransition = from.matched.length > 0
    && (isHistoryBack || toDepth < fromDepth)
    ? 'page-back'
    : 'page-forward'
})

router.afterEach((route) => {
  historyPosition = Number(window.history.state?.position ?? historyPosition)
  document.title = route.meta.title
    ? `${route.meta.title} · OrbitV`
    : 'OrbitV'
  getOrbitV()
    .then((ov) => ov.navigation.setPage({
      title: route.meta.title,
      navigationStyle: route.meta.navigationStyle,
      backgroundColor: route.meta.backgroundColor,
      navigationBarBackgroundColor: route.meta.navigationBarBackgroundColor,
      navigationBarTextStyle: route.meta.navigationBarTextStyle,
    }))
    .catch((error) => console.error('[OrbitV] 同步页面顶部栏失败', error))
})

export default router
