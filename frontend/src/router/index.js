import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    component: () => import('../views/Dashboard.vue'),
    meta: { title: '工作台' }
  },
  {
    path: '/recordings',
    component: () => import('../views/Recordings.vue'),
    meta: { title: '录音管理' }
  },
  {
    path: '/review',
    component: () => import('../views/Review.vue'),
    meta: { title: '人工复检' }
  },
  {
    path: '/dictionary',
    component: () => import('../views/Dictionary.vue'),
    meta: { title: '敏感词库' }
  },
  {
    path: '/corpus',
    component: () => import('../views/Corpus.vue'),
    meta: { title: '语料库' }
  },
  {
    // 兜底：未知路径（例如被外部容器/地址栏拼坏的 /blank）一律回到工作台，
    // 避免出现「只剩侧栏与顶栏、正文空白」的状态，同时把 URL 自愈为 /
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} · EchoLint` : 'EchoLint · 录音稽核平台'
})

export default router