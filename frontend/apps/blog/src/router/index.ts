import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '首页' },
    },
    {
      path: '/posts/:slug',
      name: 'post-detail',
      component: () => import('@/views/PostDetailView.vue'),
      meta: { title: '文章' },
    },
    {
      path: '/tags',
      name: 'tags',
      component: () => import('@/views/TagsView.vue'),
      meta: { title: '标签' },
    },
    {
      path: '/tags/:id',
      name: 'tag-posts',
      component: () => import('@/views/TagPostsView.vue'),
      meta: { title: '标签文章' },
    },
    {
      path: '/archives',
      name: 'archives',
      component: () => import('@/views/ArchivesView.vue'),
      meta: { title: '归档' },
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('@/views/SearchView.vue'),
      meta: { title: '搜索' },
    },
    {
      path: '/about',
      name: 'about',
      component: () => import('@/views/AboutView.vue'),
      meta: { title: '关于' },
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('@/views/NotFoundView.vue'),
      meta: { title: '页面不存在' },
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · Simple Notes` : 'Simple Notes'
})

export default router
