package com.armada.hyperlink.template.service;

import com.armada.hyperlink.template.model.dto.HyperlinkTemplateCreateDTO;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateQuery;
import com.armada.hyperlink.template.model.dto.HyperlinkTemplateUpdateDTO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateDetailVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateListItemVO;
import com.armada.hyperlink.template.model.vo.HyperlinkTemplateOptionVO;
import com.armada.shared.response.PageResult;
import java.util.List;

/** 超链营销模板菜单业务服务。 */
public interface HyperlinkTemplateService {

    /** 按名称、消息类型和创建时间分页查询当前租户有效模板。 */
    PageResult<HyperlinkTemplateListItemVO> list(HyperlinkTemplateQuery query);

    /** 查询当前租户模板完整详情。 */
    HyperlinkTemplateDetailVO detail(Long id);

    /** 查询未来任务选择器使用的轻量模板候选。 */
    List<HyperlinkTemplateOptionVO> options(Integer messageType, String keyword, Integer limit);

    /** 校验完整内容并创建模板，创建人取可信鉴权身份。 */
    HyperlinkTemplateDetailVO create(HyperlinkTemplateCreateDTO request, long createdBy);

    /** 按请求版本完整更新模板，不支持局部 PATCH。 */
    HyperlinkTemplateDetailVO update(Long id, HyperlinkTemplateUpdateDTO request);

    /** 复制模板业务内容，生成租户内唯一的递增副本名称。 */
    HyperlinkTemplateDetailVO copy(Long id, long createdBy);

    /** 软删除当前租户模板；一期没有任务表，无引用阻断。 */
    void delete(Long id);
}
