package com.nekocong.bulletstudy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类。
 *
 * <p>{@code @EnableScheduling} 不可省略：弹幕合批刷新（{@code FlushScheduler}）与指标推送
 * （{@code MetricsBroadcaster}）都依赖 {@code @Scheduled} 定时任务，没有本注解它们不会执行，
 * 表现为“消息只进不出、指标永不更新”。这是最容易漏掉、且症状极具迷惑性的一处配置。
 */
@SpringBootApplication
@EnableScheduling
public class BulletStudyApplication {

    public static void main(String[] args) {
        SpringApplication.run(BulletStudyApplication.class, args);
    }

}
