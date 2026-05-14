package com.mall.product;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import com.mall.product.config.CanalProperties;

@EnableDiscoveryClient
@EnableFeignClients(basePackages = "com.mall.product.mapper")
@EnableConfigurationProperties(CanalProperties.class)
@MapperScan("com.mall.product.mapper")
@SpringBootApplication(scanBasePackages = "com.mall")
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}


