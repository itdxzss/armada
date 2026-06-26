package com.armada.task.service;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.response.PageResult;
import com.armada.task.model.dto.CreateJoinTaskRequest;
import com.armada.task.model.dto.JoinTaskQuery;
import com.armada.task.model.vo.JoinResultRowVO;
import com.armada.task.model.vo.JoinTaskDetailVO;
import com.armada.task.model.vo.JoinTaskVO;

import java.util.List;

/** 进群任务业务接口(第一刀:建任务 + 读路径 + 编辑/批量软删)。 */
public interface JoinTaskService {

    /**
     * 建进群任务。
     *
     * <p>按分配方式把"选中账号 × 群链接"展开为待执行计划行落库:先对链接文本分类(含 chat.whatsapp.com
     * 为有效、其余无效),无效链接各生成一条 FAILED 明细行;有效链接按方式一(每链接固定账号数、账号跨链接连续
     * 轮询)或方式二(每账号进前 K 条链接)生成 PENDING 行。任务建为 DRAFT,total/pending 取实际 PENDING
     * 行数,引擎计数(executed/success/failed)恒 0。本方法只落库,不触发协议层执行。</p>
     *
     * @param req 建任务入参(任务名、分组/账号快照、链接文本、分配方式与间隔/重试配置)
     * @return 建成后的任务列表行视图(含 id、计数、状态)
     * @throws BusinessException 任务名称为空时抛 {@link ErrorCode#VALIDATION}
     */
    JoinTaskVO createTask(CreateJoinTaskRequest req);

    /**
     * 分页查询进群任务列表。
     *
     * @param query 查询参数(关键字/状态/分组/分配方式/间隔/时间区间 + 分页)
     * @return 任务列表行分页结果
     */
    PageResult<JoinTaskVO> listTasks(JoinTaskQuery query);

    /**
     * 进群间隔下拉选项(去重的非空 interval_label,供筛选下拉)。
     *
     * @return 间隔标签列表(升序去重)
     */
    List<String> intervalOptions();

    /**
     * 查任务详情(含 JSON 快照解析回 List,供详情页/编辑回填)。
     *
     * @param id 任务 ID
     * @return 任务详情
     * @throws BusinessException 任务不存在时抛 {@link ErrorCode#NOT_FOUND}
     */
    JoinTaskDetailVO getDetail(Long id);

    /**
     * 查任务明细行(每账号每链接一行,群链接原样直出,不脱敏)。
     *
     * @param joinTaskId 任务 ID
     * @return 明细行列表(按 id 升序)
     */
    List<JoinResultRowVO> results(Long joinTaskId);

    /**
     * 编辑进群任务(仅 DRAFT 且未执行)。
     *
     * <p>覆盖配置并按新配置重建计划行(先删旧明细再生成);total/pending 跟随新行数。
     * 已执行(status != DRAFT 或 executed > 0)的任务不可编辑。</p>
     *
     * @param id  任务 ID
     * @param req 新配置(复用建任务入参)
     * @return 编辑后的任务详情
     * @throws BusinessException 任务不存在抛 {@link ErrorCode#NOT_FOUND};已执行或任务名为空抛 {@link ErrorCode#VALIDATION}
     */
    JoinTaskDetailVO updateTask(Long id, CreateJoinTaskRequest req);

    /**
     * 批量软删进群任务(置 deleted_at,幂等)。
     *
     * @param ids 任务 id 列表;null/空返回 0
     * @return 实际软删行数(已删的不重复计)
     */
    int batchDelete(List<Long> ids);
}
