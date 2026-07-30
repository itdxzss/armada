package com.armada.group.service;

import com.armada.group.model.dto.HistoricalGroupPullCreateDTO;
import com.armada.group.model.vo.HistoricalGroupPullExecutionVO;
import java.util.Optional;
import org.springframework.web.multipart.MultipartFile;

/** 历史群单群拉人待执行创建、启动与查询服务。 */
public interface HistoricalGroupPullExecutionService {

    /**
     * 解析料子并以幂等键创建一条待执行记录及唯一成员明细。
     *
     * @param request multipart 元数据
     * @param file    TXT、CSV、XLSX 或 XLS 料子文件
     * @return 新建执行；幂等键已存在时返回原执行
     */
    HistoricalGroupPullExecutionVO create(HistoricalGroupPullCreateDTO request, MultipartFile file);

    /**
     * 重新校验服务端账号、baseline 与 fresh 邀请链接后原子启动待执行任务。
     *
     * <p>fresh 链接只作为启动门禁，worker 始终消费创建时固化的邀请链接；非待执行状态冲突，
     * 不重置成员结果，也不再次调用协议层。</p>
     *
     * @param id 待启动执行 ID
     * @return 已进入运行态的执行详情
     * @throws com.armada.shared.exception.BusinessException 执行不存在、状态冲突或门禁失败时抛出
     */
    HistoricalGroupPullExecutionVO start(Long id);

    /**
     * 查询当前租户执行及全部成员结果。
     *
     * @param id 执行 ID
     * @return 执行详情
     */
    HistoricalGroupPullExecutionVO getById(Long id);

    /**
     * 查询来源账号组在目标群最近创建的执行。
     *
     * @param sourceAccountGroupId 来源账号组 ID
     * @param groupJid  目标群 JID
     * @return 最近执行详情
     */
    Optional<HistoricalGroupPullExecutionVO> latest(Long sourceAccountGroupId, String groupJid);
}
