package com.armada.contact.task.service;

import com.armada.account.service.AccountGroupService;
import com.armada.contact.task.model.vo.ContactAccountOptionsVO;
import com.armada.promotion.channel.service.PromotionChannelService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;

/** 提供通讯录任务所需的只读筛选选项，不依赖超链钱包或创建系统分组。 */
@Service
public class ContactAccountOptionsService {

    /** 账号分组事实查询。 */
    private final AccountGroupService accountGroupService;
    /** 推广渠道事实查询。 */
    private final PromotionChannelService promotionChannelService;

    /**
     * 复用各业务域已有的租户选项查询。
     *
     * @param accountGroupService 账号分组服务
     * @param promotionChannelService 推广渠道服务
     */
    public ContactAccountOptionsService(
            AccountGroupService accountGroupService,
            PromotionChannelService promotionChannelService) {
        this.accountGroupService = accountGroupService;
        this.promotionChannelService = promotionChannelService;
    }

    /**
     * 返回当前租户的分组与渠道，不访问账号明细或渠道凭据。
     *
     * @return 真实选项，租户没有对应数据时返回空列表
     * @throws BusinessException 未建立可信租户上下文时拒绝查询
     */
    public ContactAccountOptionsVO options() {
        Long tenantId = TenantContext.get();
        if (tenantId == null || tenantId < 1) {
            throw new BusinessException(ErrorCode.TENANT_MISSING);
        }
        return new ContactAccountOptionsVO(
                accountGroupService.options(), promotionChannelService.options());
    }
}
