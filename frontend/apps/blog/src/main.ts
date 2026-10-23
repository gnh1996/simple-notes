import { configureMarkdown } from '@simple-notes/shared'
import { createPinia } from 'pinia'
import { createApp } from 'vue'

import App from './App.vue'
import router from './router'
import './styles/main.css'

configureMarkdown()

createApp(App).use(createPinia()).use(router).mount('#app')
