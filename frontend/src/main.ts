/**
 * 应用入口：创建 Vue 应用并挂载到 index.html 的 #app 上。
 * 这里只做初始化，具体能力（WebSocket、canvas、指标、控制）由后续 todo 的组件提供。
 */
import { createApp } from 'vue'
import App from './App.vue'

createApp(App).mount('#app')
