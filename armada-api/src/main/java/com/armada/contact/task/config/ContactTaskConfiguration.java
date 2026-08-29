package com.armada.contact.task.config;

import com.armada.contact.task.mapper.ContactFriendTaskAccountMapper;
import com.armada.contact.task.mapper.ContactFriendTaskMapper;
import com.armada.contact.task.service.ContactAccountFilterNormalizer;
import com.armada.contact.task.service.ContactTaskFormValidator;
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
     * @return 通讯录营销任务服务
     */
    @Bean
    public ContactTaskService contactTaskService(
            ContactFriendTaskMapper taskMapper,
            ContactFriendTaskAccountMapper accountMapper,
            ContactTaskFormValidator validator,
            ContactAccountFilterNormalizer filterNormalizer) {
        return new ContactTaskServiceImpl(
                taskMapper,
                accountMapper,
                validator,
                filterNormalizer,
                TenantContext::get,
                System::currentTimeMillis);
    }
}
