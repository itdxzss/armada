package com.armada.group.normalcreation.kafka;

import com.armada.shared.exception.BusinessException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/** 新建普群消费者的有限重试和共享 DLT 配置。 */
@Configuration
@Profile("kafka")
public class NormalGroupCreationKafkaConfiguration {

    /** 三个阶段共用一个错误处理器，不创建 retry Topic。 */
    @Bean(name = "normalGroupCreationKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> listenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            @Value("${armada.normal-group-creation.kafka.dlt-topic:group.normal-creation.commands.v1.DLT}")
            String dltTopic) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(dltTopic, -1));
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                recoverer, new FixedBackOff(1_000L, 1L));
        errorHandler.addNotRetryableExceptions(BusinessException.class);

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
