package com.armada.boot;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * armada-api 启动入口(单工程)。
 *
 * <p>{@code @MapperScan} 用 {@code annotationClass = Mapper.class} 过滤——只注册标了 MyBatis
 * {@code @Mapper} 的接口,不会误抓 service 接口和 MapStruct 的 {@code @Mapper}(同名不同类)。
 * 自动覆盖所有业务域,加新域无需改扫描配置。</p>
 */
@SpringBootApplication(scanBasePackages = "com.armada")
@MapperScan(basePackages = "com.armada", annotationClass = Mapper.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
