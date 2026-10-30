package com.rc.notify;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * API 通知系统 —— 可靠出站 HTTP 投递服务。
 *
 * <p>设计文档：API通知系统_设计文档_V2.md
 * <p>核心三件事：请求先落盘（不丢）、投递有租约（不卡死）、失败有分级（不白重试）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class NotifyApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyApplication.class, args);
    }
}
