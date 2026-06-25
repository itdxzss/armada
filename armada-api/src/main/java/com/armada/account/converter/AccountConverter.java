package com.armada.account.converter;

import com.armada.account.model.vo.AccountGroupVO;
import com.armada.account.model.vo.AccountGroupVoRow;
import com.armada.account.model.vo.AccountListVO;
import com.armada.account.model.vo.AccountListVoRow;
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

    /**
     * 账号列表投影行 → 列表出参 VO。
     *
     * <p>step1 占位字段(avatarUrl/friendsNum/groupsNum/hyperlinkSentCount/country/ipSource)
     * 恒为 null/0,step3 再接真值。</p>
     *
     * @param row Mapper 列表查询投影
     * @return 账号列表出参 VO
     */
    @Mapping(target = "avatarUrl", expression = "java(null)")
    @Mapping(target = "friendsNum", constant = "0")
    @Mapping(target = "groupsNum", constant = "0")
    @Mapping(target = "hyperlinkSentCount", constant = "0")
    @Mapping(target = "country", expression = "java(null)")
    @Mapping(target = "ipSource", expression = "java(null)")
    AccountListVO toAccountListVO(AccountListVoRow row);

    /**
     * 账号列表投影行批量 → 列表出参 VO 列表。
     *
     * @param rows Mapper 列表查询投影列表
     * @return 账号列表出参 VO 列表
     */
    List<AccountListVO> toAccountListVOList(List<AccountListVoRow> rows);
}
