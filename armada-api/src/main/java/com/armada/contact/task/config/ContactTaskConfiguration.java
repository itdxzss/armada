package com.armada.contact.task.config;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.service.AccountContactSyncService;
import com.armada.account.selection.AccountFilterSelector;
import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.mapper.ContactFriendTaskRecipientMapper;
import com.armada.contact.task.service.ContactAccountFilterNormalizer;
import com.armada.contact.task.service.ContactTaskExpansionService;
import com.armada.contact.task.service.ContactTaskFormValidator;
import com.armada.contact.task.service.ContactTaskSendResultSink;
import com.armada.contact.task.service.ContactTaskService;
import com.armada.contact.task.service.impl.ContactTaskServiceImpl;
import com.armada.shared.tenant.TenantContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 通讯录营销任务装配。 */
@Configuration
public class ContactTaskConfiguration {

    /**
     * 装配通讯录营销任务服务。
     *
     * <p>实现类的构造参数含 Supplier，Spring 无法自动装配，因此在这里显式构造。</p>
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param validator 表单校验器
     * @param filterNormalizer 账号筛选归一化器
     * @param expansionService 启用时的圈号与收件人展开服务
     * @return 通讯录营销任务服务
     */
    @Bean
    public ContactTaskService contactTaskService(
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactTaskFormValidator validator,
            ContactAccountFilterNormalizer filterNormalizer,
            ContactTaskExpansionService expansionService) {
        return new ContactTaskServiceImpl(
                taskMapper,
                accountMapper,
                validator,
                filterNormalizer,
                expansionService,
                TenantContext::get,
                System::currentTimeMillis);
    }

    /**
     * 装配通讯录任务展开服务。
     *
     * <p>实现类的构造参数含 Supplier，Spring 无法自动装配，因此在这里显式构造。</p>
     *
     * @param selector 账号圈选服务
     * @param syncService 通讯录采集服务
     * @param contactMapper 通讯录快照数据访问
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @return 展开服务
     */
    @Bean
    public ContactTaskExpansionService contactTaskExpansionService(
            AccountFilterSelector selector,
            AccountContactSyncService syncService,
            AccountContactMapper contactMapper,
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactFriendTaskRecipientMapper recipientMapper) {
        return new ContactTaskExpansionService(
                selector,
                syncService,
                contactMapper,
                taskMapper,
                accountMapper,
                recipientMapper,
                System::currentTimeMillis,
                TenantContext::get);
    }

    /**
     * 装配通讯录任务发送结果回写器。
     *
     * <p>实现类的构造参数含 Supplier，Spring 无法自动装配，因此在这里显式构造。</p>
     *
     * @param taskMapper 任务主表数据访问
     * @param accountMapper 任务账号读模型数据访问
     * @param recipientMapper 收件人明细数据访问
     * @return 发送结果回写 sink
     */
    @Bean
    public ContactTaskSendResultSink contactTaskSendResultSink(
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactFriendTaskRecipientMapper recipientMapper) {
        return new ContactTaskSendResultSink(
                taskMapper, accountMapper, recipientMapper, System::currentTimeMillis);
    }
}
