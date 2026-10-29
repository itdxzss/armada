package com.armada.platform.wbpro.model;

import java.util.List;

/** WB 集成接口（WB 切片 46 第 3.2 节）的返回模型，字段与 WB 的 camelCase JSON 一致。 */
public final class WbproModels {

    private WbproModels() {
    }

    /**
     * WB 环境开关。
     *
     * @param envId WB 环境标识
     * @param importEnabled WB 是否允许导入
     * @param shadowEnabled WB 是否开放影子连接
     * @param shadowMax 每台设备最多影子数
     * @param deviceMax 每个会员最多主设备数
     */
    public record Env(String envId, boolean importEnabled, boolean shadowEnabled, int shadowMax, int deviceMax) {
    }

    /**
     * WB 会员。
     *
     * @param id 会员 ID
     * @param username 用户名
     * @param status ACTIVE 或 BANNED
     * @param deviceCount 未删除的主设备数
     * @param deviceMax 主设备上限
     */
    public record Member(long id, String username, String status, long deviceCount, int deviceMax) {
    }

    /**
     * 发起导入配对的结果。
     *
     * @param pairingId WB 配对 ID
     * @param role MAIN 新主设备、REPAIR 重新配对、SHADOW 影子
     * @param deviceId 已有主设备 ID，MAIN 时为 null
     * @param expiresAt 配对期限（epoch 毫秒）
     */
    public record PairingStarted(String pairingId, String role, Long deviceId, long expiresAt) {
    }

    /**
     * 导入配对状态。
     *
     * @param status WAITING、CONNECTED 或 FAILED
     * @param errorCode 失败码
     * @param deviceId 成功后的主设备 ID
     */
    public record PairingState(String status, String errorCode, Long deviceId) {
    }

    /**
     * 本接入方导入的一台 WB 设备。
     *
     * @param deviceId 主设备 ID
     * @param memberId 会员 ID
     * @param username 会员用户名
     * @param connStatus 主连接状态 CONNECTED、DISCONNECTED、LOGGED_OUT
     * @param sendStatus IDLE 或 RUNNING
     * @param shadows 影子连接
     */
    public record Device(long deviceId, long memberId, String username, String connStatus, String sendStatus,
                         List<Shadow> shadows) {
    }

    /**
     * 影子连接。
     *
     * @param id 影子 ID
     * @param connStatus CONNECTED 或 DISCONNECTED
     */
    public record Shadow(long id, String connStatus) {
    }
}
