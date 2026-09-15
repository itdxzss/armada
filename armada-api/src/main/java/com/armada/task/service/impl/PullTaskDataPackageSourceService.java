package com.armada.task.service.impl;

import com.armada.resource.service.GroupDataPackageAllocationService;
import com.armada.resource.service.GroupDataPackageAllocationService.ClaimRequest;
import com.armada.resource.service.GroupDataPackageAllocationService.Phone;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.task.mapper.PullTaskMaterialMemberMapper;
import com.armada.task.model.entity.PullTaskGroupExecution;
import com.armada.task.model.entity.PullTaskMaterialMember;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 把已审查的草稿号码原子领取为正式任务资源；不创建另一套执行引擎。 */
@Service
public class PullTaskDataPackageSourceService {
    /** 限制每条绑定 SQL 的参数规模，所有分批仍处于同一提交事务。 */
    private static final int BIND_BATCH_SIZE = 500;
    private final PullTaskMaterialMemberMapper materialMapper;
    private final GroupDataPackageAllocationService allocationService;
    private final com.armada.task.service.GroupDataPackageTaskProjectionService projectionService;

    /** 构造正式提交所需的跨域资源边界。 */
    public PullTaskDataPackageSourceService(PullTaskMaterialMemberMapper materialMapper,
            GroupDataPackageAllocationService allocationService,
            com.armada.task.service.GroupDataPackageTaskProjectionService projectionService) {
        this.materialMapper = materialMapper;
        this.allocationService = allocationService;
        this.projectionService = projectionService;
    }

    /** 先结算选中包的最新任务事实，再读取未用号码快照。 */
    public List<GroupDataPackageAllocationService.Snapshot> snapshots(List<Long> packageIds, int limit) {
        projectionService.synchronize(packageIds);
        return packageIds.stream().map(id -> allocationService.snapshot(id, limit)).toList();
    }

    /** 随父任务提交事务领取全部数据包，任一冲突回滚所有号码及任务设置。 */
    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public void claim(List<PullTaskGroupExecution> executions) {
        List<PullTaskGroupExecution> packageExecutions = executions.stream()
                .filter(row -> row.getSourcePackageId() != null)
                .sorted(java.util.Comparator.comparing(PullTaskGroupExecution::getSourcePackageId))
                .toList();
        for (PullTaskGroupExecution execution : packageExecutions) {
            List<PullTaskMaterialMember> materials = materialMapper.selectByExecution(execution.getId());
            List<Long> ids = materials.stream().map(PullTaskMaterialMember::getSourcePackagePhoneId).toList();
            List<Phone> claimed = allocationService.claim(new ClaimRequest(
                    execution.getSourcePackageId(), execution.getSourcePackageGeneration(),
                    execution.getTaskId(), execution.getSeq(), ids));
            Map<Long, Phone> byId = claimed.stream().collect(Collectors.toMap(Phone::id, Function.identity()));
            for (PullTaskMaterialMember material : materials) {
                Phone phone = byId.get(material.getSourcePackagePhoneId());
                if (phone == null || !phone.phone().equals(material.getNormalizedPhone())
                        || phone.adminRequired() != Integer.valueOf(1).equals(material.getAdminRequired())) {
                    throw new BusinessException(ErrorCode.CONFLICT, "数据包号码已发生变化，请重新生成草稿");
                }
                material.setSourceAllocationVersion(phone.allocationVersion());
            }
            for (int offset = 0; offset < materials.size(); offset += BIND_BATCH_SIZE) {
                List<PullTaskMaterialMember> batch = materials.subList(
                        offset, Math.min(offset + BIND_BATCH_SIZE, materials.size()));
                if (materialMapper.bindSourceAllocations(execution.getId(), batch) != batch.size()) {
                    throw new BusinessException(ErrorCode.CONFLICT, "数据包草稿已被并发修改，请刷新后重试");
                }
            }
        }
    }
}
