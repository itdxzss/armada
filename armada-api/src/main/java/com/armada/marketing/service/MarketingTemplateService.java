package com.armada.marketing.service;

import com.armada.marketing.model.dto.MarketingTemplateDTO;
import com.armada.marketing.model.dto.MarketingTemplateQuery;
import com.armada.marketing.model.vo.MarketingTemplateVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.response.PageResult;
import java.util.List;

/**
 * 营销模板业务接口。承载「营销模板」菜单的搜索、新增、编辑、复制、批量删除能力。
 */
public interface MarketingTemplateService {

    /**
     * 分页搜索营销模板,供列表页展示。
     *
     * <p>按 ID 精准、模板名模糊、文本类型与超链模式组合筛选(多条件为且关系),
     * 结果按 ID 倒序(最新在前);分页与总数走 SQL 下推,口径一致。</p>
     *
     * @param query 搜索与分页条件
     * @return 当前页模板视图及总数
     */
    PageResult<MarketingTemplateVO> list(MarketingTemplateQuery query);

    /**
     * 新增营销模板,供后续创建营销任务时引用。
     *
     * <p>落库前校验:模板名非空且租户内不重复、内容与文本必填、超链模式合法;
     * 按钮规则——普通超链不可配按钮,按钮超链需 1~3 个且按类型校验文字与参数。</p>
     *
     * @param dto 模板配置
     * @return 创建后的模板
     * @throws BusinessException 参数校验失败或模板名已存在
     */
    MarketingTemplateVO create(MarketingTemplateDTO dto);

    /**
     * 编辑指定营销模板。
     *
     * <p>先确认模板存在,再按与新增一致的规则校验后更新;名称查重时排除自身。</p>
     *
     * @param id  模板 ID
     * @param dto 新的模板配置
     * @return 更新后的模板
     * @throws BusinessException 模板不存在或校验失败
     */
    MarketingTemplateVO update(Long id, MarketingTemplateDTO dto);

    /**
     * 复制营销模板。
     *
     * <p>基于源模板生成一条新模板,名称追加「副本」后缀;若「副本」名已存在则报冲突,
     * 提示先重命名后再复制。</p>
     *
     * @param id 源模板 ID
     * @return 复制生成的新模板
     * @throws BusinessException 源模板不存在,或副本名已存在
     */
    MarketingTemplateVO clone(Long id);

    /**
     * 批量软删除营销模板。空列表直接返回、不做任何操作。
     *
     * <p>跨域约束:被删模板若被营销任务引用,需联动停止关联任务——待任务域建成后接入。</p>
     *
     * @param ids 要删除的模板 ID 列表
     */
    void batchDelete(List<Long> ids);
}
