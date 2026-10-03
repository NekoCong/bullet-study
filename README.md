# 直播弹幕演示（live-danmaku-mvp）

一个可本地运行的“直播弹幕”学习项目：一个网页展示滚动的 Canvas 弹幕与实时指标面板；
点击按钮后，**服务端**会打开数百到上千条**真实的** WebSocket 连接回连自身，向房间灌入弹幕，
让你亲眼看到系统如何扛住约 1000 条并发连接。

技术栈：Spring Boot 3.5.16（Java 17+）· 原生 WebSocket + JSON · Redis Pub/Sub 扇出 ·
Vue 3 + Vite + TypeScript · Canvas 渲染。所有源码都带中文详细注释（学习用途）。

> 说明：本项目**不做** Docker 部署（没有后端/前端镜像、没有 Nginx、没有 compose），
> Docker 仅用于在本地跑一个 Redis 容器。也没有登录、历史记录、审核、礼物、多实例集群、CI/CD、HTTPS。

---

## 1. 前置条件

| 依赖 | 版本/说明 |
| --- | --- |
| JDK | 17 或更高（开发用 21） |
| Node.js | 20.19+ 或 22.12+（Vite 8 要求） |
| Docker | 仅用于运行 Redis 容器（Docker Desktop 即可） |
| Maven | 仓库自带 `mvnw` / `mvnw.cmd`，无需单独安装 |

---

## 2. 启动 Redis（仅用于本地验证）

使用华为云 SWR 镜像一键启动（本仓库验证时使用的命令）：

```bash
docker run -d --name bullet-redis -p 6379:6379 \
  swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/library/redis:8-alpine
```

验证：

```bash
docker exec bullet-redis redis-cli ping   # 期望输出 PONG
```

> 若该镜像不可用，可换成任意 Redis 8 镜像（如 `redis:8-alpine`），只要映射到 `6379` 即可。
> 想换端口/地址，用环境变量 `REDIS_HOST` / `REDIS_PORT` 覆盖（见 `application.yml`）。

---

## 3. 启动后端

```bash
# Linux / macOS
./mvnw spring-boot:run

# Windows（PowerShell / cmd）
mvnw.cmd spring-boot:run
```

在打开的另一个终端里，也可以打包后直接运行（便于观察启动日志、方便多次重启）：

```bash
./mvnw -DskipTests package
java -Xmx2g -jar target/bullet-study-0.0.1-SNAPSHOT.jar
```

> **为什么建议 `-Xmx2g`**：模拟器在**同一进程内**回连自身，1000 目标连接时进程里会同时持有
> 客户端 + 服务端约 2000 个 socket，堆与文件描述符都要留足余量。见第 7 节“调优旋钮”。

启动成功后：
- 后端监听 `http://localhost:8080`
- 健康检查 `GET /api/health`：Redis 可达返回 `200 {"status":"UP"}`，不可达返回 `503 {"status":"DOWN"}`
  （**Redis 不可用时应用仍会正常启动**，只是模拟器无法启动。）

---

## 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 Vite 提示的地址（默认 `http://localhost:5173`）。
前端通过 Vite 的 `/api` 与 `/ws` 代理访问后端，因此**组件里没有硬编码后端地址**。

---

## 5. 页面怎么用

1. 顶部徽标显示 WebSocket 连接状态（已连接 / 连接中 / 未连接）。
2. 底部“模拟控制区”填入参数（默认 `connections=100`、`messagesPerConnection=10`、
   `ratePerSecond=500`、`roomId=room-1`），点击 **START**。
3. 观察左侧 Canvas 中滚动的弹幕、右侧指标面板的 9 项指标变化。
4. **STOP** 会关闭所有虚拟连接，连接数回落。

---

## 6. 接口契约

### REST 接口（共 5 个）

| 方法 | 路径 | 说明 | 主要状态码 |
| --- | --- | --- | --- |
| POST | `/api/sim/start` | 启动模拟器，打开 N 条真实连接 | `202` 已接受（返回 `runId`）· `409` 已有模拟在跑 · `503` Redis 不可用 |
| POST | `/api/sim/stop` | 停止模拟器并关闭全部虚拟连接 | `200` |
| GET | `/api/sim/status` | 查询模拟状态 | `200` |
| GET | `/api/metrics` | 指标快照（**始终 200**，空闲时为全 0） | `200` |
| GET | `/api/health` | 健康检查（探测 Redis） | `200` Redis 正常 · `503` Redis 不可用 |

`POST /api/sim/start` 请求体：

```json
{ "connections": 1000, "messagesPerConnection": 10, "ratePerSecond": 500, "roomId": "room-1" }
```

`GET /api/sim/status` 响应体：

```json
{ "running": false, "connections": 1000, "sent": 10000, "failed": 0 }
```

`GET /api/metrics` 响应体（9 个字段，字段顺序即契约顺序）：

```json
{
  "connections": 0,
  "totalReceived": 0,
  "totalPublished": 0,
  "totalBroadcast": 0,
  "publishRate": 0,
  "broadcastRate": 0,
  "avgLatencyMs": 0,
  "p99LatencyMs": 0,
  "dropped": 0
}
```

字段含义：
- `connections`：当前在线连接数（gauge）。
- `totalReceived`：被接收并解析的弹幕条数。
- `totalPublished`：发布到 Redis 的条数。
- `totalBroadcast`：按“收件人”入队的次数（一条弹幕发给 N 个会话即 +N）。
- `publishRate` / `broadcastRate`：最近 1 秒的滑动窗口速率。
- `avgLatencyMs` / `p99LatencyMs`：端到端延迟（仅在客户端提供 `ts` 且时钟合理时统计）。
- `dropped`：业务合批队列因超上限而**丢弃的最旧**消息条数。
- 投递健康度 = `1 - dropped / (totalBroadcast + dropped)`，验收期望 `>= 0.95`。

### WebSocket 帧契约（2 种类型）

端点：`ws://localhost:8080/ws/danmaku?roomId=room-1`（房间号随连接走，不放在消息体里）。

**客户端 → 服务端**（发送一条弹幕；`ts` 可选，用于延迟统计）：

```json
{ "type": "danmaku", "text": "666" }
```

**服务端 → 客户端，弹幕帧**（每 ~50ms 合批下发一帧）：

```json
{
  "type": "danmaku",
  "items": [
    { "type": "danmaku", "roomId": "room-1", "text": "666", "ts": 1730000000000, "serverTs": 1730000000012 }
  ]
}
```

**服务端 → 客户端，指标帧**（每秒一帧）：

```json
{
  "type": "metrics",
  "connections": 1000,
  "totalReceived": 10000,
  "totalPublished": 10000,
  "totalBroadcast": 9999959,
  "publishRate": 26,
  "broadcastRate": 26000,
  "avgLatencyMs": 0,
  "p99LatencyMs": 0,
  "dropped": 700581
}
```

前端按**顶层 `type`** 判别帧类型（TS 判别联合）：`danmaku` 追加到渲染缓冲，`metrics` 刷新面板，
其余一律忽略。

---

## 7. 自连接（模拟器回连自身）调优旋钮

模拟器在应用进程内打开真实 socket 回连自身，因此进程内 socket 数约为目标连接数的 2 倍。
以下参数可按机器能力调整（均在 `src/main/resources/application.yml`，支持环境变量覆盖）：

| 配置项 | 默认 | 环境变量 | 作用 |
| --- | --- | --- | --- |
| `server.tomcat.max-connections` | 8192 | `SERVER_TOMCAT_MAX_CONNECTIONS` | Tomcat 最大连接数 |
| `server.tomcat.threads.max` | 400 | `SERVER_TOMCAT_THREADS_MAX` | 工作线程上限 |
| `server.tomcat.accept-count` | 200 | `SERVER_TOMCAT_ACCEPT_COUNT` | 握手等待队列长度 |
| `danmaku.flush-interval-ms` | 50 | - | 合批刷新间隔（越小越实时、帧越多） |
| `danmaku.max-buffer-per-session` | 200 | - | 单会话队列上限，超限丢弃最旧消息 |
| `danmaku.send-time-limit-ms` | 5000 | - | 单次发送时间上限（超时关闭该会话） |
| `danmaku.buffer-size-limit-bytes` | 1048576 | - | 单会话发送缓冲上限（1MB） |

JVM 堆：建议 `-Xmx2g`（见第 3 节）。

> **Windows 提示**：Windows 没有 `ulimit`。如果本机拒绝 1000 个 socket，请降低
> `connections` 到本机可接受的最大值，并记录实际达成的连接数。
> 另外，投递健康度受“刷新吞吐”影响：在 1000 连接下，聚合速率 500/s 时刷新线程会追不上，
> `dropped` 上升（约 6.5%）；把速率降到约 200/s（1000×10 约 50s）即可做到 `dropped=0`、健康度 1.0。
> 这是本演示在单进程自连接下的已知吞吐拐点，详见 `.omo/evidence/live-danmaku-mvp/`。

---

## 8. 目录速览

```
src/main/java/com/nekocong/bulletstudy/
  config/     WebSocketConfig            # /ws/danmaku 装配
  realtime/   DanmakuWebSocketHandler    # 连接生命周期 + 入站解析
  realtime/   SessionRegistry            # 房间 -> 在线会话
  danmaku/    BroadcastService           # 校验/发布/本地入队（合批队列）
  danmaku/    FlushScheduler             # 每 50ms 合批下发
  redis/      RedisConfig / *Fanout*      # pub/sub 装配、发布、订阅
  metrics/    MetricsRegistry / *         # 计数器、速率、延迟、REST、WS 推送
  sim/        SimulationService / *       # 服务端真实连接模拟器
frontend/src/
  types.ts                    # 线协议类型（判别联合）
  lib/parseFrame.ts           # 帧解析（纯函数）
  stores/danmaku.ts           # 单例状态
  composables/useDanmakuSocket.ts  # 连接 + 路由
  components/                 # DanmakuCanvas / MetricsPanel / SimulationControls
```
