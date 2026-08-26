package com.cgn.faultdetect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 故障检测系统 - Java 版启动类
 * 端口默认 8000（application.yml 可改）
 */
@SpringBootApplication
public class FaultDetectApplication {
    public static void main(String[] args) {
        SpringApplication.run(FaultDetectApplication.class, args);
    }
}
