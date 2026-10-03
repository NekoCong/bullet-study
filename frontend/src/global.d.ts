// 全局类型补充：把渲染循环暴露的帧计数器挂到 window 上，供自动化 QA 断言“动画确实在跑”。
// 单独的 .d.ts 让组件里可以安全使用 window.__danmakuFrames，而无需 `any`。
export {}

declare global {
  interface Window {
    /**
     * 弹幕画布已渲染的帧数（每执行一次 requestAnimationFrame 回调 +1）。
     * 仅用于可视化验证：只要它持续增长，就证明 RAF 渲染循环存活。
     */
    __danmakuFrames?: number
  }
}
