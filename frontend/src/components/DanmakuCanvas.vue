<script setup lang="ts">
/**
 * DanmakuCanvas.vue — Canvas 弹幕渲染器（滚动弹幕 + 对象池 + RAF 循环）。
 *
 * 在整体数据流中的位置：
 *   store 缓冲区（由 useDanmakuSocket 写入）
 *        │  每帧 takePending()
 *        ▼
 *   spawn() 分配到空闲“泳道”（lane） ──▶ update() 左移 ──▶ draw() 整屏重绘（整数坐标）
 *
 * 性能要点（对应 MDN "Optimizing canvas"）：
 * - 单个 requestAnimationFrame 循环，绝不用 setInterval；
 * - 不给每条弹幕创建 DOM 节点，全部画在一张 canvas 上；
 * - 关闭 alpha（alpha:false），避免每帧透明合成开销；
 * - 使用整数坐标（Math.round），避免亚像素抗锯齿带来的额外光栅化；
 * - 文本宽度做缓存（Map），避免每帧重复 measureText；
 * - 弹幕对象从对象池复用，减少 GC 抖动；
 * - 按“泳道”避免同一行弹幕互相重叠。
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useDanmakuStore } from '../stores/danmaku'

/** 每个车道的高度（像素）。 */
const LANE_HEIGHT = 30
/** 同车道两条弹幕之间的最小水平间距（像素）。 */
const BULLET_GAP = 24
/** 弹幕最小/最大水平速度（像素/秒）。 */
const MIN_SPEED = 90
const MAX_SPEED = 170
/** 文本宽度缓存上限，超过则清空重建（防止长跑内存膨胀）。 */
const WIDTH_CACHE_CAP = 1000
/** 屏幕同时存在的弹幕上限，超过则丢弃新弹幕（渲染侧背压）。 */
const MAX_ACTIVE_BULLETS = 600
/** 画布字体：一次设定，绘制与测量共用。 */
const FONT = '16px "Microsoft YaHei", "Segoe UI", sans-serif'
/** 弹幕配色循环。 */
const PALETTE = ['#e6e9f0', '#ffd166', '#8ecae6', '#ff9f9f', '#b8e986', '#c9a7ff']

/** 一条活动弹幕（对象池复用载体）。 */
interface Bullet {
  text: string
  /** 文本左边缘的 x 坐标（随时间减小）。 */
  x: number
  /** 文本基线 y 坐标。 */
  y: number
  /** 文本像素宽度（用于判断完全移出屏幕）。 */
  width: number
  /** 水平速度（像素/秒）。 */
  speed: number
  color: string
}

const store = useDanmakuStore()
const canvasEl = ref<HTMLCanvasElement | null>(null)

let ctx: CanvasRenderingContext2D | null = null
let rafId = 0
let lastTimestamp = 0
let cssWidth = 0
let cssHeight = 0
/** 每个车道“再次可用”的时间戳（performance.now() 毫秒）。 */
let laneFreeAt: number[] = []
const bullets: Bullet[] = []
const pool: Bullet[] = []
const widthCache = new Map<string, number>()
let colorCursor = 0
let resizeObserver: ResizeObserver | null = null

/**
 * 初始化/重建画布尺寸（含 devicePixelRatio 适配）。
 *
 * 关键：先把 canvas 的位图尺寸设成 CSS 尺寸 × dpr，再用 setTransform 把绘制坐标系
 * 统一到 CSS 像素，这样后续所有坐标都按 CSS 像素书写，dpr 适配对业务透明。
 */
function setupCanvas(): void {
  const canvas = canvasEl.value
  if (!canvas) {
    return
  }
  const dpr = window.devicePixelRatio || 1
  cssWidth = canvas.clientWidth
  cssHeight = canvas.clientHeight
  canvas.width = Math.max(1, Math.floor(cssWidth * dpr))
  canvas.height = Math.max(1, Math.floor(cssHeight * dpr))
  // alpha:false：画布不透明，省去每帧的透明合成。
  ctx = canvas.getContext('2d', { alpha: false })
  if (ctx) {
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  }
  // 尺寸变化后旧坐标全部失效，清空活动弹幕并重建车道。
  bullets.splice(0, bullets.length)
  const laneCount = Math.max(1, Math.floor(cssHeight / LANE_HEIGHT))
  laneFreeAt = new Array<number>(laneCount).fill(0)
}

/**
 * 测量文本宽度（带缓存）。
 *
 * @param text 文本
 * @returns CSS 像素宽度
 */
function measureWidth(text: string): number {
  if (!ctx) {
    return text.length * 16
  }
  const cached = widthCache.get(text)
  if (cached !== undefined) {
    return cached
  }
  ctx.font = FONT
  const width = ctx.measureText(text).width
  if (widthCache.size >= WIDTH_CACHE_CAP) {
    widthCache.clear()
  }
  widthCache.set(text, width)
  return width
}

/**
 * 生成一条弹幕并分配到空闲车道；无空闲车道时丢弃（渲染侧背压）。
 *
 * @param text 弹幕文本
 */
function spawn(text: string): void {
  if (!ctx || laneFreeAt.length === 0 || bullets.length >= MAX_ACTIVE_BULLETS) {
    return
  }
  const now = performance.now()
  const width = measureWidth(text)
  const speed = MIN_SPEED + Math.random() * (MAX_SPEED - MIN_SPEED)
  let laneIndex = -1
  for (let i = 0; i < laneFreeAt.length; i++) {
    if (laneFreeAt[i] <= now) {
      laneIndex = i
      break
    }
  }
  if (laneIndex < 0) {
    // 所有车道都在“上一辆”的尾流里：丢弃本条，避免重叠与卡顿。
    return
  }
  // 车道被占用到“本条完全进入屏幕（左移一个自身宽度）之后”，保证不追尾。
  laneFreeAt[laneIndex] = now + ((width + BULLET_GAP) / speed) * 1000
  const color = PALETTE[colorCursor % PALETTE.length]
  colorCursor++
  const y = laneIndex * LANE_HEIGHT + LANE_HEIGHT - 8
  const recycled = pool.pop()
  if (recycled) {
    recycled.text = text
    recycled.x = cssWidth
    recycled.y = y
    recycled.width = width
    recycled.speed = speed
    recycled.color = color
    bullets.push(recycled)
  } else {
    bullets.push({ text, x: cssWidth, y, width, speed, color })
  }
}

/**
 * 更新所有弹幕位置（左移），并把移出屏幕的弹幕归还对象池。
 *
 * @param dt 距上一帧的秒数（已在上层夹取上限，避免卡顿后瞬移）
 */
function update(dt: number): void {
  for (let i = bullets.length - 1; i >= 0; i--) {
    const bullet = bullets[i]
    bullet.x -= bullet.speed * dt
    if (bullet.x + bullet.width < 0) {
      // 交换删除：把数组尾部元素挪到当前位置，避免 splice 的整体搬移。
      const last = bullets.pop()
      if (last && i < bullets.length) {
        bullets[i] = last
      }
      pool.push(bullet)
    }
  }
}

/** 整屏重绘（清底 + 逐条文字）。 */
function draw(): void {
  if (!ctx) {
    return
  }
  ctx.fillStyle = '#0b0d12'
  ctx.fillRect(0, 0, cssWidth, cssHeight)
  ctx.font = FONT
  ctx.textBaseline = 'alphabetic'
  for (const bullet of bullets) {
    ctx.fillStyle = bullet.color
    // 整数坐标：避免亚像素渲染带来的模糊与额外光栅化开销。
    ctx.fillText(bullet.text, Math.round(bullet.x), Math.round(bullet.y))
  }
}

/**
 * RAF 帧回调：计数 → 取新弹幕 → 更新 → 绘制 → 预约下一帧。
 *
 * @param timestamp 浏览器提供的单调时间戳（毫秒）
 */
function frame(timestamp: number): void {
  const dt = lastTimestamp === 0 ? 0 : Math.min(0.05, (timestamp - lastTimestamp) / 1000)
  lastTimestamp = timestamp
  // 帧计数器：自动化验证只需断言它持续增长即可证明渲染循环存活。
  window.__danmakuFrames = (window.__danmakuFrames ?? 0) + 1

  const pending = store.takePending()
  for (const item of pending) {
    spawn(item.text)
  }
  update(dt)
  draw()
  rafId = window.requestAnimationFrame(frame)
}

onMounted(() => {
  setupCanvas()
  const canvas = canvasEl.value
  if (canvas) {
    // 容器尺寸变化时重建画布（例如窗口缩放、面板折叠）。
    resizeObserver = new ResizeObserver(() => {
      setupCanvas()
    })
    resizeObserver.observe(canvas)
  }
  rafId = window.requestAnimationFrame(frame)
})

onBeforeUnmount(() => {
  // 必须取消 RAF，否则组件卸载后回调仍在跑（内存泄漏 + 无谓 CPU）。
  window.cancelAnimationFrame(rafId)
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<template>
  <canvas ref="canvasEl" class="danmaku-canvas" aria-label="弹幕画布"></canvas>
</template>

<style scoped>
.danmaku-canvas {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 420px;
  background: #0b0d12;
  border-radius: var(--radius-md, 10px);
}
</style>
