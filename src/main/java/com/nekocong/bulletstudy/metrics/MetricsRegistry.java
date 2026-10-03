package com.nekocong.bulletstudy.metrics;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * 进程内指标注册表：所有计数器与速率窗口的唯一汇聚点。
 *
 * <p><b>端到端链路中的位置：</b>本类是“观测层”，被链路上的每个关键站点调用：
 * <pre>
 *   SessionRegistry.register/remove ──▶ recordConnectionOpened/Closed（在线连接 gauge）
 *   BroadcastService.ingest          ──▶ recordReceived + recordLatency（收 / 延迟）
 *   RedisFanoutPublisher.publish     ──▶ recordPublished（发布）
 *   BroadcastService.broadcastLocal  ──▶ recordBroadcast（按收件人入队）
 *   BroadcastService（drop-oldest）  ──▶ recordDropped（丢弃）
 *   MetricsController / MetricsBroadcaster ──▶ snapshot()（读）
 * </pre>
 *
 * <p><b>为什么用 {@link LongAdder} 而不是 {@code AtomicLong}：</b>
 * 本项目高并发下计数器是“多写少读”（每条消息都会累加，指标只每秒读一次）。
 * {@code LongAdder} 把热点分散到多个 cell，写竞争远小于单一 CAS 的 AtomicLong；
 * 代价是 {@code sum()} 得到的是弱一致值——对监控指标完全可接受。
 *
 * <p><b>速率窗口：</b>用 10 个 100ms 的桶滚动求和，得到“最近 1 秒”的速率。
 * 桶内计数复用 {@link LongAdder}，通过“时间戳 epoch 不匹配就清零”来淘汰过期数据，
 * 无需后台线程或额外内存分配。
 *
 * <p><b>延迟与时钟偏移：</b>延迟样本只在 {@code ts > 0 && serverTs >= ts} 时采集，
 * 避免客户端时钟回拨/未校准产生负值污染平均值与 P99。样本存在固定长度环形数组里，
 * 只保留最近 {@value #LATENCY_RING_SIZE} 条，保证内存有界。
 */
@Component
public class MetricsRegistry {

    /** 速率窗口的桶数量：10 桶 × 100ms = 1 秒。 */
    private static final int RATE_BUCKETS = 10;

    /** 每个速率桶代表的时间片（毫秒）。 */
    private static final long RATE_BUCKET_MS = 100L;

    /** 延迟样本环形数组容量：固定内存、覆盖最近若干个样本即可。 */
    private static final int LATENCY_RING_SIZE = 1024;

    /** 当前在线连接数（gauge）。用 AtomicInteger 因为需要精确的加减与读取。 */
    private final AtomicInteger connections = new AtomicInteger(0);

    /** 累计接收的弹幕条数。 */
    private final LongAdder totalReceived = new LongAdder();

    /** 累计发布到 Redis 的弹幕条数。 */
    private final LongAdder totalPublished = new LongAdder();

    /** 累计按“收件人”入队的次数（一条弹幕发给 N 个会话即 +N）。 */
    private final LongAdder totalBroadcast = new LongAdder();

    /** 累计因合批队列超过上限而被丢弃的最旧消息条数。 */
    private final LongAdder dropped = new LongAdder();

    /** “发布”速率的 1 秒滑动窗口。 */
    private final RateWindow publishWindow = new RateWindow();

    /** “广播（入队）”速率的 1 秒滑动窗口。 */
    private final RateWindow broadcastWindow = new RateWindow();

    /** 延迟样本环形缓冲（毫秒）。 */
    private final AtomicLongArray latencyRing = new AtomicLongArray(LATENCY_RING_SIZE);

    /** 环形缓冲的写指针（单调递增，取模后作为下标）。 */
    private final AtomicLong latencyIndex = new AtomicLong(0);

    /** 已采集的延迟样本总数（用于区分“环形缓冲未填满”的情况）。 */
    private final LongAdder latencyCount = new LongAdder();

    /**
     * 连接建立：在线数 +1。仅在该会话确实被登记进注册表后调用，避免重复计数。
     */
    public void recordConnectionOpened() {
        this.connections.incrementAndGet();
    }

    /**
     * 连接关闭：在线数 -1。仅在该会话确实从注册表移除后调用。
     */
    public void recordConnectionClosed() {
        this.connections.updateAndGet(current -> Math.max(0, current - 1));
    }

    /**
     * 记录一条被接收的弹幕（{@code totalReceived}）。
     */
    public void recordReceived() {
        this.totalReceived.increment();
    }

    /**
     * 记录一次成功的 Redis 发布：累加 {@code totalPublished} 并推进发布速率窗口。
     */
    public void recordPublished() {
        this.totalPublished.increment();
        this.publishWindow.increment();
    }

    /**
     * 记录一次“向某个会话入队”：累加 {@code totalBroadcast} 并推进广播速率窗口。
     */
    public void recordBroadcast() {
        this.totalBroadcast.increment();
        this.broadcastWindow.increment();
    }

    /**
     * 记录一次“因队列超限丢弃最旧消息”。
     */
    public void recordDropped() {
        this.dropped.increment();
    }

    /**
     * 采集一条延迟样本（毫秒）。
     *
     * @param clientTs 客户端时间戳（毫秒）；&lt;=0 视为“客户端未提供”，直接忽略
     * @param serverTs 服务端接收时间戳（毫秒）
     */
    public void recordLatency(long clientTs, long serverTs) {
        // 时钟偏移防护：只接受非负且在因果上合理的样本（服务端不可能早于客户端发送）。
        if (clientTs <= 0 || serverTs < clientTs) {
            return;
        }
        long delta = serverTs - clientTs;
        long index = this.latencyIndex.getAndIncrement();
        this.latencyRing.set((int) (index % LATENCY_RING_SIZE), delta);
        this.latencyCount.increment();
    }

    /**
     * 生成当前指标快照（供 HTTP 拉取与 WebSocket 推送共用）。
     *
     * @return 不可变快照；空闲时各项为 0，速率窗口为空时速率为 0
     */
    public MetricsSnapshot snapshot() {
        long[] latency = latencyStats();
        return new MetricsSnapshot(
                this.connections.get(),
                this.totalReceived.sum(),
                this.totalPublished.sum(),
                this.totalBroadcast.sum(),
                this.publishWindow.ratePerSecond(),
                this.broadcastWindow.ratePerSecond(),
                latency[0],
                latency[1],
                this.dropped.sum());
    }

    /**
     * 计算延迟的平均值与 P99。
     *
     * <p>环形缓冲填满后只保留最近 {@value #LATENCY_RING_SIZE} 条样本；排序在副本上进行，
     * 不修改环形数组本身。样本量固定有界，单次排序开销可控。
     *
     * @return 长度为 2 的数组：[0]=平均毫秒（取整），[1]=P99 毫秒；无样本时全 0
     */
    private long[] latencyStats() {
        int sampleCount = (int) Math.min(this.latencyCount.sum(), LATENCY_RING_SIZE);
        if (sampleCount == 0) {
            return new long[] {0L, 0L};
        }
        long[] samples = new long[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            samples[i] = this.latencyRing.get(i);
        }
        Arrays.sort(samples);
        long sum = 0L;
        for (long value : samples) {
            sum += value;
        }
        long average = sum / sampleCount;
        // P99：向上取整定位，再夹取到合法下标，避免 n=1 等边界。
        int p99Index = (int) Math.ceil(0.99d * sampleCount) - 1;
        if (p99Index < 0) {
            p99Index = 0;
        }
        if (p99Index >= sampleCount) {
            p99Index = sampleCount - 1;
        }
        return new long[] {average, samples[p99Index]};
    }

    /**
     * 1 秒滑动窗口速率计数器。
     *
     * <p><b>工作原理：</b>把时间切成 100ms 的格子，用 {@code epoch = now/100}。
     * 每个桶记录自己最后一次被写入的 epoch；当某个桶再次被写入而 epoch 已经前进，
     * 说明该桶的数据已过期，直接清零后复用（无需定时清理）。
     * 求和时只累加“时间戳落在最近 10 个 epoch 内”的桶，因此结果就是最近约 1 秒的计数。
     *
     * <p>所有读写都在 {@code synchronized} 下进行：与写并发的场景很少（指标线程每秒一次），
     * 锁竞争可忽略，换来实现简单不易错。
     */
    private static final class RateWindow {

        private final LongAdder[] counters = new LongAdder[RATE_BUCKETS];
        private final long[] stamps = new long[RATE_BUCKETS];

        private RateWindow() {
            for (int i = 0; i < RATE_BUCKETS; i++) {
                this.counters[i] = new LongAdder();
                this.stamps[i] = Long.MIN_VALUE;
            }
        }

        /** 记一次事件，归入当前 100ms 桶。 */
        private synchronized void increment() {
            long epoch = System.currentTimeMillis() / RATE_BUCKET_MS;
            int index = (int) (epoch % RATE_BUCKETS);
            if (this.stamps[index] != epoch) {
                // 该桶已被更早的时间片占用，超过一个窗口，直接清零复用。
                this.stamps[index] = epoch;
                this.counters[index].reset();
            }
            this.counters[index].increment();
        }

        /**
         * @return 最近约 1 秒内的事件总数，即“每秒速率”
         */
        private synchronized long ratePerSecond() {
            long epoch = System.currentTimeMillis() / RATE_BUCKET_MS;
            long total = 0L;
            for (int i = 0; i < RATE_BUCKETS; i++) {
                // 只统计落在当前窗口内的桶；更早的桶即使还没被复用也不计入。
                if (this.stamps[i] > epoch - RATE_BUCKETS) {
                    total += this.counters[i].sum();
                }
            }
            return total;
        }
    }
}
