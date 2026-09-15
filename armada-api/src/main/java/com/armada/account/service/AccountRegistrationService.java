package com.armada.account.service;

import com.armada.account.model.dto.AccountRegistrationCreateDTO;
import com.armada.account.model.dto.AccountRegistrationQuery;
import com.armada.account.model.vo.AccountRegistrationCatalogVO;
import com.armada.account.model.vo.AccountRegistrationDetailVO;
import com.armada.account.model.vo.AccountRegistrationTaskVO;
import com.armada.platform.sms.grizzly.model.GrizzlyPriceTier;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 租户接码注册任务入口；创建只持久化采购意图，后台执行真实操作。 */
public interface AccountRegistrationService {
    /** 返回真实WhatsApp/美国目录及配置与远端能力门禁。 */
    AccountRegistrationCatalogVO catalog();
    /** 查询美国目录项各价格档位，库存仅为快照。 */
    List<GrizzlyPriceTier> priceTiers(String countryId);
    /** 固定次数下单，同租户requestId幂等，参数冲突拒绝。 */
    AccountRegistrationDetailVO create(AccountRegistrationCreateDTO request);
    /** 当前租户任务SQL分页。 */
    PageResult<AccountRegistrationTaskVO> list(AccountRegistrationQuery query);
    /** 当前租户任务与其固定次数明细。 */
    AccountRegistrationDetailVO detail(Long id);
    /** 取消尚未采购明细，已在途条目继续收尾，不承诺退款。 */
    AccountRegistrationDetailVO cancel(Long id);
}
