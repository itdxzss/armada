package com.armada.boot.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.unit.DataSize;

/** 真实应用 YAML 必须生成足以承载新群链接模式一次提交的 Servlet 上传配置。 */
class MultipartUploadConfigurationTest {

    private final WebApplicationContextRunner applicationRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MultipartAutoConfiguration.class))
            .withInitializer(context -> {
                try {
                    new YamlPropertySourceLoader()
                            .load("application", new FileSystemResource("src/main/resources/application.yml"))
                            .forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                } catch (IOException exception) {
                    throw new IllegalStateException("读取 application.yml 失败", exception);
                }
            });

    @Test
    void frameworkDefaultsCannotCarryFiftyTwoMegabyteFiles() {
        MultipartConfigElement config = new MultipartProperties().createMultipartConfig();

        assertThat(config.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(1).toBytes());
        assertThat(config.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(10).toBytes());
    }

    @Test
    void applicationConfigAllowsFiftyFilesAndMultipartOverhead() {
        applicationRunner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MultipartConfigElement.class);
            MultipartConfigElement config = context.getBean(MultipartConfigElement.class);

            assertThat(config.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(2).toBytes());
            assertThat(config.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(110).toBytes());
            assertThat(config.getMaxRequestSize()).isGreaterThan(50 * config.getMaxFileSize());
        });
    }

    @Test
    void springEnvironmentVariablesOverrideApplicationDefaults() {
        applicationRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("test-environment", Map.of(
                        "SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE", "3MB",
                        "SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE", "120MB"))))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(MultipartConfigElement.class);
                    MultipartConfigElement config = context.getBean(MultipartConfigElement.class);

                    assertThat(config.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(3).toBytes());
                    assertThat(config.getMaxRequestSize()).isEqualTo(DataSize.ofMegabytes(120).toBytes());
                });
    }
}
