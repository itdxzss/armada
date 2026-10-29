package com.armada.platform.wbpro;

import com.armada.platform.wbpro.model.WbproTarget;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本环境可导入的 WB 目标（WB 切片 46 多环境对接）。
 *
 * <p>targets 格式：{@code envId|显示名|https://域名|clientId|secret}，多个目标用分号分隔；为空表示本环境不对接 WB。
 * envId 必须与目标 WB 的 WBPRO_ENV_ID 一致，每个响应都会核对，防止测试环境连到生产 WB。密钥只在内存里，不写日志。</p>
 */
@ConfigurationProperties(prefix = "armada.wbpro")
public class WbproProperties {

    private static final int TARGET_FIELDS = 5;
    private static final int MIN_SECRET_LENGTH = 32;

    /** 原始目标配置串。 */
    private String targets = "";
    /** 单次请求超时。 */
    private Duration timeout = Duration.ofSeconds(30);

    public String getTargets() {
        return targets;
    }

    public void setTargets(String targets) {
        this.targets = targets == null ? "" : targets;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * 解析全部目标；配置有误时抛出异常，启动即失败，不在运行中静默丢掉某个目标。
     *
     * @return 已解析的目标列表，未配置时为空列表
     */
    public List<WbproTarget> parsedTargets() {
        List<WbproTarget> out = new ArrayList<>();
        for (String entry : targets.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            String[] parts = entry.trim().split("\\|", -1);
            if (parts.length != TARGET_FIELDS) {
                throw new IllegalArgumentException("WB_TARGETS 每项必须是 envId|名称|baseUrl|clientId|secret");
            }
            WbproTarget target = new WbproTarget(parts[0].trim(), parts[1].trim(), parts[2].trim(),
                    parts[3].trim(), parts[4].trim());
            validate(target, out);
            out.add(target);
        }
        return List.copyOf(out);
    }

    /**
     * 按 envId 查找目标。
     *
     * @param envId WB 环境标识
     * @return 目标；未配置时为空
     */
    public Optional<WbproTarget> target(String envId) {
        return parsedTargets().stream().filter(target -> target.envId().equals(envId)).findFirst();
    }

    private static void validate(WbproTarget target, List<WbproTarget> existing) {
        boolean validUrl = target.baseUrl().startsWith("https://")
                || target.baseUrl().startsWith("http://127.0.0.1")
                || target.baseUrl().startsWith("http://localhost");
        if (!target.envId().matches("[a-z0-9-]{2,32}") || target.name().isEmpty() || !validUrl
                || !target.clientId().matches("[a-z0-9-]{3,32}") || target.secret().length() < MIN_SECRET_LENGTH
                || existing.stream().anyMatch(other -> other.envId().equals(target.envId()))) {
            throw new IllegalArgumentException("WB_TARGETS 存在无效或重复的目标: " + target.envId());
        }
    }
}
