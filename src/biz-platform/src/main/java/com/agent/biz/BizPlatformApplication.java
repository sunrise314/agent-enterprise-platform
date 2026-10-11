package com.agent.biz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 企业智能体平台 - 业务服务入口。
 *
 * 基于 AgentScope-Java（Apache 2.0）二次开发的商用级企业智能体平台自研业务层。
 */
@SpringBootApplication
public class BizPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(BizPlatformApplication.class, args);
    }
}
