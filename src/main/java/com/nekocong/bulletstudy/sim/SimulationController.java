package com.nekocong.bulletstudy.sim;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nekocong.bulletstudy.redis.RedisFanoutPublisher;

/**
 * 模拟器 REST 控制器。
 *
 * <p><b>端到端链路中的位置：</b>
 * <pre>
 *   POST /api/sim/start  ──202/409/503──▶ SimulationService.start
 *   POST /api/sim/stop   ──200─────────▶ SimulationService.stop
 *   GET  /api/sim/status ──200─────────▶ SimulationService.status
 * </pre>
 *
 * <p><b>状态码语义（务求可观测）：</b>
 * <ul>
 *   <li><b>202 Accepted</b>：已接受并异步开始（模拟是后台任务，不等它跑完）；</li>
 *   <li><b>409 Conflict</b>：已有模拟在运行——同一时刻只允许一轮，避免计数互相污染；</li>
 *   <li><b>503 Service Unavailable</b>：Redis 不可用。模拟依赖 Redis 扇出，提前失败比跑一轮空转更好。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/sim")
public class SimulationController {

    private final SimulationService simulationService;

    private final RedisFanoutPublisher fanoutPublisher;

    /**
     * 构造器注入。
     *
     * @param simulationService 模拟服务
     * @param fanoutPublisher   借其 ping 做启动前 Redis 预检
     */
    public SimulationController(SimulationService simulationService, RedisFanoutPublisher fanoutPublisher) {
        this.simulationService = simulationService;
        this.fanoutPublisher = fanoutPublisher;
    }

    /**
     * 启动一轮模拟。
     *
     * @param request 参数（Bean Validation 在进入方法前已校验）
     * @return 202 + runId；Redis 不可用 503；已在运行 409
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, String>> start(@Valid @RequestBody SimulationRequest request) {
        if (!this.fanoutPublisher.pingRedis()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "redis unavailable"));
        }
        String runId = this.simulationService.start(request);
        if (runId == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "simulation already running"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("runId", runId));
    }

    /**
     * 停止当前模拟并关闭所有虚拟连接。
     *
     * @return 停止后的状态
     */
    @PostMapping("/stop")
    public SimulationStatus stop() {
        return this.simulationService.stop();
    }

    /**
     * 查询模拟状态（供前端轮询 / 验收脚本使用）。
     *
     * @return 运行标志、连接数、已发送、失败计数
     */
    @GetMapping("/status")
    public SimulationStatus status() {
        return this.simulationService.status();
    }
}
