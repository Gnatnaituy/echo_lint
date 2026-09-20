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
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.afterEach((to) => {
  document.title = to.meta?.title ? `${to.meta.title} - 录音稽核平台` : '录音稽核平台'
})

export default router