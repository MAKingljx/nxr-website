import router from './router'
import { ElMessage } from 'element-plus'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { getToken } from '@/utils/auth'
import { loginLocation, safeLoginTarget } from '@/utils/loginRedirect'
import { isHttp, isPathMatch } from '@/utils/validate'
import { isRelogin } from '@/utils/request'
import useUserStore from '@/store/modules/user'
import useLockStore from '@/store/modules/lock'
import useSettingsStore from '@/store/modules/settings'
import usePermissionStore from '@/store/modules/permission'
import { legacyWorkspaceLocation, workspacePath } from '@/utils/submissionWorkspace'
import { canViewDashboard } from '@/utils/dashboardAccess'

NProgress.configure({ showSpinner: false })

const whiteList = ['/login', '/register']

function businessLandingPath(target) {
  if (!['/', '/index'].includes(target.path)) return null
  const user = useUserStore()
  const permissions = user.permissions || []
  if (canViewDashboard(permissions)) return null
  if (permissions.includes('nxr:agent:workbench') && router.hasRoute('NxrWorkspaceClients')) {
    return workspacePath('clients')
  }
  return usePermissionStore().landingPath || '/401'
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
      return { path: safeLoginTarget(to.query.redirect) }
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
        // Resolve the landing page only after permission-filtered routes exist.
        const landing = businessLandingPath(to)
        if (landing) return { path: landing, query: to.query, hash: to.hash, replace: true }
        return { ...to, replace: true }
      } catch (err) {
        await useUserStore().logOut()
        isRelogin.show = false
        ElMessage.error(err)
        return loginLocation(to.fullPath)
      }
    }
    const legacy = legacyLanding(to)
    if (legacy) return legacy
    const landing = businessLandingPath(to)
    if (landing) return { path: landing, query: to.query, hash: to.hash, replace: true }
    return true
  } else {
    // 没有token
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      return true
    }
    NProgress.done()
    return loginLocation(to.fullPath)
  }
})

router.afterEach(() => {
  NProgress.done()
})
