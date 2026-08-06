package com.armada.group.normalcreation.kafka;

import com.armada.group.normalcreation.model.dto.NormalGroupCreationCommand;
import com.armada.group.normalcreation.service.NormalGroupCreationExecutionService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 新建普群三个业务 Topic 的消费者入口。 */
@Component
@Profile("kafka")
public class NormalGroupCreationStageConsumer {

    private final ObjectMapper objectMapper;
    private final NormalGroupCreationExecutionService executionService;

    public NormalGroupCreationStageConsumer(
            ObjectMapper objectMapper,
            NormalGroupCreationExecutionService executionService) {
        this.objectMapper = objectMapper;
        this.executionService = executionService;
    }

    /** 消费双向联系人准备阶段。 */
    @KafkaListener(
            topics = "${armada.normal-group-creation.kafka.prepare-topic:group.normal-creation.contact-prepare.v1}",
            groupId = "${armada.normal-group-creation.kafka.prepare-group-id:armada-normal-group-contact-prepare-v1}",
            concurrency = "${armada.normal-group-creation.kafka.prepare-concurrency:4}",
            containerFactory = "normalGroupCreationKafkaListenerContainerFactory")
    public void onPrepare(String rawMessage) {
        consume(rawMessage, "PREPARE");
    }

    /** 消费一次性建群阶段。 */
    @KafkaListener(
            topics = "${armada.normal-group-creation.kafka.create-topic:group.normal-creation.create.v1}",
            groupId = "${armada.normal-group-creation.kafka.create-group-id:armada-normal-group-create-v1}",
            concurrency = "${armada.normal-group-creation.kafka.create-concurrency:4}",
            containerFactory = "normalGroupCreationKafkaListenerContainerFactory")
    public void onCreate(String rawMessage) {
        consume(rawMessage, "CREATE");
    }

    /** 消费权限、登记和可选退群阶段。 */
    @KafkaListener(
            topics = "${armada.normal-group-creation.kafka.post-process-topic:group.normal-creation.post-process.v1}",
            groupId = "${armada.normal-group-creation.kafka.post-process-group-id:armada-normal-group-post-process-v1}",
            concurrency = "${armada.normal-group-creation.kafka.post-process-concurrency:4}",
            containerFactory = "normalGroupCreationKafkaListenerContainerFactory")
    public void onPostProcess(String rawMessage) {
        consume(rawMessage, "POST_PROCESS");
    }

    private void consume(String rawMessage, String expectedAction) {
        NormalGroupCreationCommand command;
        try {
            command = objectMapper.readValue(rawMessage, NormalGroupCreationCommand.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群阶段消息 JSON 非法");
        }
        if (!expectedAction.equals(command.action())) {
            throw new BusinessException(ErrorCode.VALIDATION, "新建普群消息与 Topic 阶段不匹配");
        }
        executionService.execute(command);
    }
}
