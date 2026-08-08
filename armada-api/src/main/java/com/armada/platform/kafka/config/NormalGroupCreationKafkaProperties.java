package com.armada.platform.kafka.config;

import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 新建普群跨协议 Kafka 通道配置。
 *
 * <p>Web、Android 命令和统一结果各使用独立 topic，避免与其它协议业务共享消费容量。</p>
 */
@ConfigurationProperties(prefix = "armada.normal-group-creation.kafka")
public class NormalGroupCreationKafkaProperties implements InitializingBean {

    public static final String DEFAULT_WEB_COMMAND_TOPIC =
            "protocol.web.normal-group.commands.v1";
    public static final String DEFAULT_ANDROID_COMMAND_TOPIC =
            "protocol.android.normal-group.commands.v1";
    public static final String DEFAULT_RESULT_TOPIC = "protocol.normal-group.events.v1";
    public static final String DEFAULT_RESULT_GROUP_ID = "armada-api-normal-group-results";
    public static final int DEFAULT_RESULT_CONCURRENCY = 4;

    private String webCommandTopic = DEFAULT_WEB_COMMAND_TOPIC;
    private String androidCommandTopic = DEFAULT_ANDROID_COMMAND_TOPIC;
    private String resultTopic = DEFAULT_RESULT_TOPIC;
    private String resultGroupId = DEFAULT_RESULT_GROUP_ID;
    private int resultConcurrency = DEFAULT_RESULT_CONCURRENCY;

    @Override
    public void afterPropertiesSet() {
        webCommandTopic = requiredText(webCommandTopic, "Web 命令 topic");
        androidCommandTopic = requiredText(androidCommandTopic, "Android 命令 topic");
        resultTopic = requiredText(resultTopic, "结果 topic");
        resultGroupId = requiredText(resultGroupId, "结果 consumer group");
        if (resultConcurrency <= 0) {
            throw new IllegalArgumentException("新建普群结果 consumer 并发度必须大于 0");
        }
        Set<String> topics = new HashSet<>(Set.of(
                webCommandTopic, androidCommandTopic, resultTopic));
        if (topics.size() != 3) {
            throw new IllegalArgumentException("新建普群三个 Kafka topic 必须互不重复");
        }
    }

    private static String requiredText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("新建普群" + name + "不能为空");
        }
        return value.trim();
    }

    public String getWebCommandTopic() {
        return webCommandTopic;
    }

    public void setWebCommandTopic(String webCommandTopic) {
        this.webCommandTopic = webCommandTopic;
    }

    public String getAndroidCommandTopic() {
        return androidCommandTopic;
    }

    public void setAndroidCommandTopic(String androidCommandTopic) {
        this.androidCommandTopic = androidCommandTopic;
    }

    public String getResultTopic() {
        return resultTopic;
    }

    public void setResultTopic(String resultTopic) {
        this.resultTopic = resultTopic;
    }

    public String getResultGroupId() {
        return resultGroupId;
    }

    public void setResultGroupId(String resultGroupId) {
        this.resultGroupId = resultGroupId;
    }

    public int getResultConcurrency() {
        return resultConcurrency;
    }

    public void setResultConcurrency(int resultConcurrency) {
        this.resultConcurrency = resultConcurrency;
    }
}
