import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import Login from '../views/Login.vue'
import Dashboard from '../views/Dashboard.vue'
import Monitor from '../views/Monitor.vue'
import GateMonitor from '../views/GateMonitor.vue'
import ParkingMonitor from '../views/ParkingMonitor.vue'
import Heatmap from '../views/Heatmap.vue'
import DeviceManagement from '../views/DeviceManagement.vue'
import ModelManagement from '../views/ModelManagement.vue'
import WhitelistManagement from '../views/WhitelistManagement.vue'
import Records from '../views/Records.vue'
import Alerts from '../views/Alerts.vue'
import Statistics from '../views/Statistics.vue'
import SceneAnomaly from '../views/SceneAnomaly.vue'
import MediaRecognition from '../views/MediaRecognition.vue'
import { isLoggedIn } from '../utils/auth.js'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: Login,
      meta: { public: true, title: '登录' }
    },
    {
      path: '/',
      component: MainLayout,
      children: [
        { path: '', redirect: '/monitor' },
        { path: 'monitor', name: 'monitor', component: Monitor, meta: { title: '监控中心' } },
        { path: 'gate', name: 'gate', component: GateMonitor, meta: { title: '闸机监控' } },
        { path: 'parking', name: 'parking', component: ParkingMonitor, meta: { title: '禁停监控' } },
        { path: 'heatmap', name: 'heatmap', component: Heatmap, meta: { title: '拥堵热力图' } },
        { path: 'records', name: 'records', component: Records, meta: { title: '通行记录' } },
        { path: 'alerts', name: 'alerts', component: Alerts, meta: { title: '告警中心' } },
        { path: 'statistics', name: 'statistics', component: Statistics, meta: { title: '数据统计和历史查询' } },
        { path: 'scene-anomaly', name: 'scene-anomaly', component: SceneAnomaly, meta: { title: '场景异常' } },
        { path: 'media-recognition', name: 'media-recognition', component: MediaRecognition, meta: { title: '离线识别' } },
        { path: 'dashboard', name: 'dashboard', component: Dashboard, meta: { title: '系统管理' } },
        { path: 'devices', name: 'devices', component: DeviceManagement, meta: { title: '设备管理' } },
        { path: 'models', name: 'models', component: ModelManagement, meta: { title: '模型管理' } },
        { path: 'whitelist', name: 'whitelist', component: WhitelistManagement, meta: { title: '白名单管理' } },
        { path: 'settings', redirect: '/devices' }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.meta.public) {
    if (to.path === '/login' && isLoggedIn()) {
      return { path: '/monitor' }
    }
    return true
  }
  if (!isLoggedIn()) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
