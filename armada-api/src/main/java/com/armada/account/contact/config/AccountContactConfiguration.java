package com.armada.account.contact.config;

import com.armada.account.contact.mapper.AccountContactMapper;
import com.armada.account.contact.mapper.AccountContactSyncMapper;
import com.armada.account.contact.service.AccountContactNormalizer;
import com.armada.account.contact.service.impl.AccountContactSnapshotSink;
import com.armada.account.mapper.AccountStateMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 账号通讯录快照装配。通讯录由协议层周期推送，armada 不再主动拉。 */
@Configuration
@EnableConfigurationProperties(AccountContactProperties.class)
public class AccountContactConfiguration {

    /**
     * 装配协议通讯录快照落库处理器。
     *
     * @param contactMapper 联系人快照数据访问
     * @param syncMapper 同步状态数据访问
     * @param accountStateMapper 账号状态数据访问
     * @param normalizer 协议快照归一化器
     * @return 快照落库处理器
     */
    @Bean
    public AccountContactSnapshotSink accountContactSnapshotSink(
            AccountContactMapper contactMapper,
            AccountContactSyncMapper syncMapper,
            AccountStateMapper accountStateMapper,
            AccountContactNormalizer normalizer) {
        return new AccountContactSnapshotSink(
                contactMapper, syncMapper, accountStateMapper, normalizer,
                System::currentTimeMillis);
    }
}
