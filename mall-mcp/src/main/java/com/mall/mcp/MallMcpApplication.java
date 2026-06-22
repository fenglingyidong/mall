package com.mall.mcp;

import com.mall.mcp.config.MallMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MallMcpProperties.class)
public class MallMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(MallMcpApplication.class, args);
    }
}
