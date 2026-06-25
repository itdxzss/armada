package com.armada.account.converter;

import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.model.vo.AccountGroupVoRow;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * account 域对象转换(MapStruct,编译期生成)。
 *
 * <p>时间字段均为 Long epoch 毫秒(BIGINT),直映无需 toEpochMilli 转换。</p>
 */
@Mapper(componentModel = "spring")
public interface AccountConverter {

    /**
     * Mapper 投影行 → 出参 VO。
     *
     * <p>onlineCount 暂恒为 0(step3 再接在线汇总)。</p>
     *
     * @param row Mapper 查询投影
     * @return 前端出参
     */
    @Mapping(target = "onlineCount", constant = "0L")
    AccountGroupVO toGroupVO(AccountGroupVoRow row);

    /**
     * 批量转换。
     *
     * @param rows Mapper 查询投影列表
     * @return 前端出参列表
     */
    List<AccountGroupVO> toGroupVOList(List<AccountGroupVoRow> rows);
}
