import { createApp } from 'vue'

import App from './App.vue'
import { installErrorLogging } from './lib/logger'
import './styles/main.css'

const app = createApp(App)
installErrorLogging(app)
app.mount('#app')
