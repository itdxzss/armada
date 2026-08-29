package com.armada.contact.task.scheduler;

import com.armada.account.selection.mapper.AccountFilterSelectionMapper;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.service.ContactTaskMessageCommandFactory;
import com.armada.platform.protocol.port.MessageSendPort;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.util.Random;

/**
 * 通讯录营销调度装配。
 *
 * <p>参数类在所有运行模式下都要可注入，调度 bean 只在 {@code kafka} profile 下建立。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ContactTaskSchedulerProperties.class)
public class ContactTaskSchedulerConfiguration {

    /**
     * 通讯录调度专用时钟。与营销调度分开命名，避免 bean 名冲突。
     *
     * @return UTC 系统时钟
     */
    @Bean
    @Profile("kafka")
    public Clock contactTaskClock() {
        return Clock.systemUTC();
    }

    /**
     * 发送间隔随机源。集中成 bean，测试里可换成固定种子。
     *
     * @return 随机源
     */
    @Bean
    @Profile("kafka")
    public Random contactTaskSendIntervalRandom() {
        return new Random();
    }

    /**
     * 装配轮次执行器，并把排干收尾接到生命周期推进器。
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @param selectionMapper 账号协议事实复查
     * @param commandFactory 消息命令组装器
     * @param messageSendPort 协议 outbox 端口
     * @param properties 调度参数
     * @param contactTaskClock 系统时钟
     * @param contactTaskSendIntervalRandom 发送间隔随机源
     * @param lifecycleWorker 生命周期推进器
     * @return 轮次执行器
     */
    @Bean
    @Profile("kafka")
    public ContactTaskRoundWorker contactTaskRoundWorker(
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactFriendTaskRecipientMapper recipientMapper,
            AccountFilterSelectionMapper selectionMapper,
            ContactTaskMessageCommandFactory commandFactory,
            MessageSendPort messageSendPort,
            ContactTaskSchedulerProperties properties,
            Clock contactTaskClock,
            Random contactTaskSendIntervalRandom,
            ContactTaskLifecycleWorker lifecycleWorker) {
        return new ContactTaskRoundWorker(
                taskMapper,
                accountMapper,
                recipientMapper,
                selectionMapper,
                commandFactory,
                messageSendPort,
                properties,
                contactTaskClock,
                contactTaskSendIntervalRandom,
                lifecycleWorker::completeDrainedTask);
    }
}
