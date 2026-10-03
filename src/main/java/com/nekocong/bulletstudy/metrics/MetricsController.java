package com.nekocong.bulletstudy.metrics;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nekocong.bulletstudy.redis.RedisFanoutPublisher;

/**
 * 指标与健康检查 REST 控制器。
 *
 * <p><b>端到端链路中的位置：</b>对外暴露“观测面”，供前端指标面板、模拟器与运维探针读取：
 * <pre>
 *   GET /api/metrics ──▶ MetricsRegistry.snapshot()（9 字段，始终 200）
 *   GET /api/health  ──▶ RedisFanoutPublisher.pingRedis()（200 / 503）
 * </pre>
 *
 * <p><b>为什么 /api/metrics 永远返回 200：</b>指标接口本身不依赖 Redis——即使 Redis 挂了，
 * 前端仍需要一个稳定的数据源来显示“当前为零/连接数”等状态。把“Redis 是否可用”单独交给
 * /api/health（用状态码表达），职责清晰：一个负责“读数”，一个负责“探活”。
 */
@RestController
@RequestMapping("/api")
public class MetricsController {

    private final MetricsRegistry metricsRegistry;

    private final RedisFanoutPublisher fanoutPublisher;

    /**
     * 构造器注入。
     *
     * @param metricsRegistry 指标注册表
     * @param fanoutPublisher Redis 发布器（借其 ping 能力做健康检查）
     */
    public MetricsController(MetricsRegistry metricsRegistry, RedisFanoutPublisher fanoutPublisher) {
        this.metricsRegistry = metricsRegistry;
        this.fanoutPublisher = fanoutPublisher;
    }

    /**
     * 读取当前指标快照。
     *
     * @return 9 字段快照；空闲时为全 0，绝不 500
     */
    @GetMapping("/metrics")
    public MetricsSnapshot metrics() {
        return this.metricsRegistry.snapshot();
    }

    /**
     * 探活：Redis 可达返回 200，不可达返回 503。
     *
     * <p>返回 503 而非 500 是语义选择：这是“依赖不可用”的临时状态，不是服务端 bug。
     *
     * @return 200 {@code {"status":"UP"}} 或 503 {@code {"status":"DOWN"}}
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        boolean redisUp = this.fanoutPublisher.pingRedis();
        if (redisUp) {
            return ResponseEntity.ok(Map.of("status", "UP"));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
    }
}
