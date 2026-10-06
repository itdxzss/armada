package com.armada.task.model.dto;

import java.util.List;

/** 两种无草稿创建入口共用的内部合同；HTTP 仅绑定各自受限的 record。 */
public interface PullTaskDirectCreateRequest {
    /** @return 客户端幂等请求 UUID */
    String requestId();
    /** @return 任务名称 */
    String taskName();
    /** @return 任务备注 */
    String remark();
    /** @return 创建后是否自动启动 */
    Integer autoStart();
    /** @return 链接模式来源分组，自建群为空 */
    Long groupFolderId();
    /** @return 链接模式手工链接，自建群为空 */
    String linksText();
    /** @return 选中的料子数据包 */
    List<Long> packageIds();
    /** @return 前期每次人数 */
    Integer earlyPullCount();
    /** @return 前期固定次数，精简新群固定0 */
    Integer earlyPullCallCount();
    /** @return 单次拉人数下限 */
    Integer pullCountMin();
    /** @return 单次拉人数上限 */
    Integer pullCountMax();
    /** @return 同群拉人间隔下限（秒） */
    Integer pullIntervalSeconds();
    /** @return 每群拉手数 */
    Integer pullerCountPerGroup();
    /** @return 每次随料子加入的站台数 */
    Integer stationCountPerCall();
    /** @return 同时执行的群数 */
    Integer concurrentGroupCount();
    /** @return 拉手来源分组 */
    Long pullerGroupId();
    /** @return 站台来源分组 */
    Long stationGroupId();
    /** @return 拉手完成归档分组 */
    Long pullerFinishGroupId();

    /** 由入口固定非业务可配置项，禁止把旧草稿、联系人及提权选项带入。 */
    PullTaskStandardCreateDTO frozenSettings();
}
