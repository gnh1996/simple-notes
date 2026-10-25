import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import { initTheme } from './composables/useTheme'
import { setupDirectives } from './directives/perm'
import router from './router'
// Element Plus 样式在 main.css 中按 CSS 层导入（保证 Tailwind 工具类优先级）
import './styles/main.css'

initTheme()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
setupDirectives(app)
app.mount('#app')
