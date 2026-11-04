import { createApp } from 'vue'

import App from './App.vue'
import { initTheme } from './composables/useTheme'
import { installErrorLogging } from './lib/logger'
import './styles/main.css'

initTheme()

const app = createApp(App)
installErrorLogging(app)
app.mount('#app')
