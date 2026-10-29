package com.armada.account.wbcompanion.model.vo;

import java.util.List;

/** 登录到 WB 分身的出参。 */
public final class WbCompanionVOs {

    private WbCompanionVOs() {
    }

    /**
     * 可导入的 WB 环境。
     *
     * @param envId WB 环境标识
     * @param name 显示名
     */
    public record WbTargetVO(String envId, String name) {
    }

    /**
     * WB 会员与目标环境开关。
     *
     * @param id 会员 ID
     * @param username 用户名
     * @param status ACTIVE 或 BANNED
     * @param deviceCount 未删除的主设备数
     * @param deviceMax 主设备上限
     * @param shadowEnabled WB 是否开放影子连接；关闭时只能登录 1 个
     */
    public record WbMemberVO(long id, String username, String status, long deviceCount, int deviceMax,
                             boolean shadowEnabled) {
    }

    /**
     * 批量发起结果，逐个账号返回。
     *
     * @param items 每个账号的结果
     */
    public record WbCompanionCreateResultVO(List<Item> items) {

        /**
         * 单个账号的发起结果。
         *
         * @param accountId 账号 ID
         * @param taskId 已创建的任务 ID；未创建时为 null
         * @param errorCode 未创建的原因码
         * @param errorMessage 未创建的原因说明
         */
        public record Item(Long accountId, Long taskId, String errorCode, String errorMessage) {
        }
    }

    /**
     * 一次登录任务的进度。
     *
     * @param id 任务 ID
     * @param accountId 账号 ID
     * @param wsPhone 号码
     * @param wbEnvId WB 环境标识
     * @param wbUsername WB 会员用户名
     * @param baseCount 开始时已有的连接数
     * @param targetCount 目标连接数
     * @param succeededCount 本任务已成功的连接数
     * @param status 1 进行中 2 成功 3 失败 4 已停止
     * @param step 当前这一条的步骤：0 待发起 1 WB 已发起 2 安卓已输入配对码
     * @param wbRole 当前这一条在 WB 的角色
     * @param errorCode 失败码
     * @param createdAt 创建时间
     * @param finishedAt 结束时间
     * @param retryAfter 失败后可再次发起的时间；为 null 表示不限制
     */
    public record WbCompanionTaskVO(Long id, Long accountId, String wsPhone, String wbEnvId, String wbUsername,
                                    int baseCount, int targetCount, int succeededCount, int status, int step,
                                    String wbRole, String errorCode, Long createdAt, Long finishedAt,
                                    Long retryAfter) {
    }

    /**
     * 账号列表"WB 分身"列，读本地快照，不实时调 WB。
     *
     * @param accountId 账号 ID
     * @param wbEnvId 绑定的 WB 环境
     * @param wbUsername 绑定的 WB 会员
     * @param connectionCount 最近同步的连接数
     * @param onlineCount 最近同步的在线连接数
     * @param runningTaskId 进行中的任务 ID
     * @param runningDone 进行中任务已有的连接数（已有 + 本次成功）
     * @param runningTarget 进行中任务的目标连接数
     */
    public record WbCompanionSummaryVO(Long accountId, String wbEnvId, String wbUsername, int connectionCount,
                                       int onlineCount, Long runningTaskId, int runningDone, int runningTarget) {
    }

    /**
     * 账号的 WB 分身详情，打开时实时查询 WB 并刷新快照。
     *
     * @param accountId 账号 ID
     * @param wbEnvId 绑定的 WB 环境；未绑定为 null
     * @param wbEnvName 环境显示名
     * @param wbMemberId 会员 ID
     * @param wbUsername 会员用户名
     * @param wbDeviceId WB 主设备 ID
     * @param live 本次是否成功查到 WB；false 时连接列表为上次快照不可用
     * @param connections 连接列表，主连接在前
     * @param tasks 最近的登录任务，新的在前
     */
    public record WbCompanionDetailVO(Long accountId, String wbEnvId, String wbEnvName, Long wbMemberId,
                                      String wbUsername, Long wbDeviceId, boolean live,
                                      List<ConnectionVO> connections, List<WbCompanionTaskVO> tasks) {
    }

    /**
     * WB 上的一条连接。
     *
     * @param role MAIN 主连接或 SHADOW 影子
     * @param wbId WB 设备或影子 ID
     * @param connStatus CONNECTED、DISCONNECTED、LOGGED_OUT
     */
    public record ConnectionVO(String role, long wbId, String connStatus) {
    }
}
