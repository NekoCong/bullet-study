/**
 * Vite 构建/开发服务器配置。
 *
 * 为什么需要下面的 /api 与 /ws 代理：
 * - 前端开发服务器（默认 http://localhost:5173）与 Spring Boot 后端（http://localhost:8080）是
 *   两个不同源的服务。浏览器直接请求 http://localhost:8080 会触发跨域（CORS），而且 WebSocket
 *   握手也不能靠简单的 CORS 头解决。
 * - 因此把 /api 与 /ws 前缀交给 Vite 开发服务器转发到后端：浏览器始终只访问同源
 *   http://localhost:5173/api/... 和 /ws/danmaku，由 Vite 在服务端转发，前端代码里看不到后端地址
 *   （这样后端换端口/域名时只改这一处，组件里不需要硬编码 http://localhost:8080）。
 * - `/ws` 必须设置 ws: true，否则 Vite 会按普通 HTTP 请求转发，WebSocket 的 Upgrade 握手会失败。
 *
 * 注意：这些代理只在 `npm run dev` 的开发服务器生效；生产部署（本学习项目不做）需要由真正
 * 的反向代理承担同样的职责。
 */
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  // 支持 .vue 单文件组件的编译
  plugins: [vue()],
  server: {
    proxy: {
      // REST 接口：/api/sim/*、/api/metrics、/api/health 等
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // WebSocket 接口：/ws/danmaku；ws: true 表示按 WebSocket 协议升级转发
      '/ws': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
    },
  },
})
