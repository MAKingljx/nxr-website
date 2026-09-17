import router from './router'
import { ElMessage } from 'element-plus'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { getToken } from '@/utils/auth'
import { isHttp, isPathMatch } from '@/utils/validate'
import { isRelogin } from '@/utils/request'
import useUserStore from '@/store/modules/user'
import useLockStore from '@/store/modules/lock'
import useSettingsStore from '@/store/modules/settings'
import usePermissionStore from '@/store/modules/permission'
import { legacyWorkspaceLocation, workspacePath } from '@/utils/submissionWorkspace'

NProgress.configure({ showSpinner: false })

const whiteList = ['/login', '/register']

function agentLandingPath(target) {
  if (!['/', '/index'].includes(target.path)) return null
  const user = useUserStore()
  const permissions = user.permissions || []
  if (user.roles.includes('admin') || permissions.includes('*:*:*') || permissions.includes('nxr:dashboard:view')) return null
  const agentOnly = user.roles.length === 1 && user.roles.includes('nxr_agent')
  return (agentOnly || permissions.includes('nxr:agent:workbench')) && router.hasRoute('NxrWorkspaceClients') ? workspacePath('clients') : null
}

function legacyLanding(target) {
  return router.hasRoute('NxrWorkspaceClients') ? legacyWorkspaceLocation(target) : null
}

const isWhiteList = (path) => {
  return whiteList.some(pattern => isPathMatch(pattern, path))
}

router.beforeEach(async (to, from) => {
  NProgress.start()
  if (getToken()) {
    to.meta.title && useSettingsStore().setTitle(to.meta.title)
    const isLock = useLockStore().isLock
    if (to.path === '/login') {
      NProgress.done()
      return { path: '/' }
    }
    if (isWhiteList(to.path)) {
      return true
    }
    if (isLock && to.path !== '/lock') {
      NProgress.done()
      return { path: '/lock' }
    }
    if (!isLock && to.path === '/lock') {
      NProgress.done()
      return { path: '/' }
    }
    if (useUserStore().roles.length === 0) {
      isRelogin.show = true
      try {
        // 拉取user_info信息
        await useUserStore().getInfo()
        isRelogin.show = false
        // 根据roles权限生成可访问的路由
        const accessRoutes = await usePermissionStore().generateRoutes()
        accessRoutes.forEach(route => {
          if (!isHttp(route.path)) {
            router.addRoute(route)
          }
        })
        const legacy = legacyLanding(to)
        if (legacy) return legacy
        // Resolve the agent landing page only after the permission-filtered routes exist.
        const landing = agentLandingPath(to)
        if (landing) return { path: landing, query: to.query, hash: to.hash, replace: true }
        return { ...to, replace: true }
      } catch (err) {
        await useUserStore().logOut()
        ElMessage.error(err)
        return { path: '/' }
      }
    }
    const legacy = legacyLanding(to)
    if (legacy) return legacy
    const landing = agentLandingPath(to)
    if (landing) return { path: landing, query: to.query, hash: to.hash, replace: true }
    return true
  } else {
    // 没有token
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      return true
    }
    NProgress.done()
    return `/login?redirect=${to.fullPath}` // 否则全部重定向到登录页
  }
})

router.afterEach(() => {
  NProgress.done()
})
