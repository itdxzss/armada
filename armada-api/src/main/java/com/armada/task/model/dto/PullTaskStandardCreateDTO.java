package com.armada.task.model.dto;

/**
 * 普通群链接任务的提交冻结入参。
 *
 * <p>字段口径见设计文档 4.1。审核模式、次管理、退群方式、群资料与权限设置、归档分组等
 * 本期排除项<b>不在本合同内</b>，出现即校验失败——静默忽略会让前端误以为配置已生效。</p>
 *
 * @param draftTaskId          草稿任务 ID
 * @param version              草稿任务乐观锁版本
 * @param taskName             任务名称，1-128 字符
 * @param remark               备注，可空，不超过 512 字符
 * @param autoStart            创建后是否自动启动：0 否 1 是
 * @param materialAdminTiming  料子内管理员设置时点：1 入群后立即 2 本群料子全部终态后
 * @param pullCountMin         单次拉人料子人数下限，不含站台
 * @param pullCountMax         单次拉人料子人数上限，不小于下限
 * @param pullIntervalSeconds  同一拉手连续拉人调用的最小间隔秒数
 * @param pullerCountPerGroup  每条执行行的计划拉手数
 * @param stationCountPerCall  每次拉人调用叠加的站台数
 * @param concurrentGroupCount 同一父任务最大同时运行执行行数
 * @param pullerRiskMinutes    拉手风控冷却分钟；0 表示不建立定时恢复
 * @param managerGroupId       管理账号分组 ID
 * @param pullerGroupId        拉手账号分组 ID
 * @param stationGroupId       站台账号分组 ID
 */
public record PullTaskStandardCreateDTO(Long draftTaskId, Integer version, String taskName,
                                        String remark, Integer autoStart,
                                        Integer materialAdminTiming, Integer pullCountMin,
                                        Integer pullCountMax, Integer pullIntervalSeconds,
                                        Integer pullerCountPerGroup, Integer stationCountPerCall,
                                        Integer concurrentGroupCount, Integer pullerRiskMinutes,
                                        Long managerGroupId, Long pullerGroupId,
                                        Long stationGroupId) {
}
