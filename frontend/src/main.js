import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { t } from './i18n'
import './styles/index.css'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// Element Plus 内置文案（分页 / 日期 / 空态等）语言由 App.vue 的 <el-config-provider> 跟随站点语言，
// 这里不再固定中文；默认语言为英文，首帧在 provider 生效前也不会出现中文内置文案。
app.use(ElementPlus)
app.use(router)

// 首屏标题跟随语言（路由守卫也可能在 /api/auth/me 会话恢复期间停留片刻）
document.title = `${t('app.name')} · ${t('app.subtitle')}`

// 等首次导航的路由守卫完成（含 /api/auth/me 会话恢复）再挂载，
// 避免未登录时先闪现带壳层的工作台再跳登录页。
router.isReady().then(() => app.mount('#app'))
