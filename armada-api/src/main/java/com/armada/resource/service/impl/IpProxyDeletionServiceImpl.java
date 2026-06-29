package com.armada.resource.service.impl;

import com.armada.account.service.AccountOnlineCommandService;
import com.armada.resource.mapper.IpProxyMapper;
import com.armada.resource.service.IpProxyDeletionService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IP 代理删除编排服务实现。
 */
@Service
public class IpProxyDeletionServiceImpl implements IpProxyDeletionService {

    private static final Logger log = LoggerFactory.getLogger(IpProxyDeletionServiceImpl.class);

    private final IpProxyMapper ipProxyMapper;
    private final AccountOnlineCommandService accountOnlineCommandService;

    public IpProxyDeletionServiceImpl(IpProxyMapper ipProxyMapper,
                                      AccountOnlineCommandService accountOnlineCommandService) {
        this.ipProxyMapper = ipProxyMapper;
        this.accountOnlineCommandService = accountOnlineCommandService;
    }

    /**
     * 批量删除 IP 代理。
     *
     * <p>删除前先交给账号域处理在线绑定账号:在线账号换 IP 重登,离线账号不动。
     * 账号重登编排成功后才软删代理;如果重登阶段抛出业务异常,本方法不会执行软删。</p>
     *
     * @param ids 要删除的代理 ID 列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDelete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }

        accountOnlineCommandService.reloginOnlineAccountsByProxyIds(ids);
        int deleted = ipProxyMapper.softDeleteByIds(ids, System.currentTimeMillis());
        log.info("IP代理删除编排完成 requested={} deleted={}", ids.size(), deleted);
    }
}
