import { configureMarkdown } from '@simple-notes/shared'
import { createApp } from 'vue'

import App from './App.vue'
import './styles/main.css'

configureMarkdown()

createApp(App).mount('#app')
