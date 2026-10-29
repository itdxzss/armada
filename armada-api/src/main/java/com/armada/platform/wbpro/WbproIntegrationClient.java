package com.armada.platform.wbpro;

import com.armada.platform.wbpro.model.WbproModels;
import com.armada.platform.wbpro.model.WbproTarget;
import java.util.List;
import java.util.Optional;

/**
 * 调用 WB 集成接口（WB 切片 46 第 3 节）。每次请求按目标的密钥签名，每个响应核对 envId；
 * 失败统一抛 {@link WbproIntegrationException}。
 */
public interface WbproIntegrationClient {

    /**
     * 读取 WB 环境开关，也用作连通性检查。
     *
     * @param target WB 目标
     * @return 环境开关
     */
    WbproModels.Env env(WbproTarget target);

    /**
     * 按用户名精确查 WB 会员。
     *
     * @param target WB 目标
     * @param username 用户名
     * @return 会员；不存在或不是会员时为空
     */
    Optional<WbproModels.Member> member(WbproTarget target, String username);

    /**
     * 发起一条导入配对。
     *
     * @param target WB 目标
     * @param command 配对参数，含配对码
     * @return WB 判定的角色和配对 ID
     */
    WbproModels.PairingStarted startPairing(WbproTarget target, WbproPairingCommand command);

    /**
     * 查询导入配对状态；WB 记录过期时抛 PAIRING_NOT_FOUND。
     *
     * @param target WB 目标
     * @param pairingId 配对 ID
     * @return 配对状态
     */
    WbproModels.PairingState pairing(WbproTarget target, String pairingId);

    /**
     * 查询本接入方导入的、该号码未删除的设备。
     *
     * @param target WB 目标
     * @param wsPhone 号码
     * @return 设备列表，最多 1 台
     */
    List<WbproModels.Device> devices(WbproTarget target, String wsPhone);

    /**
     * 解绑本接入方导入的设备，WB 登出全部连接。
     *
     * @param target WB 目标
     * @param deviceId WB 主设备 ID
     */
    void unbind(WbproTarget target, long deviceId);

    /**
     * 导入配对参数。
     *
     * @param memberId WB 会员 ID
     * @param phone 号码
     * @param pairingCode 8 位配对码，不得输出
     * @param importRef Armada 任务引用
     */
    record WbproPairingCommand(long memberId, String phone, String pairingCode, String importRef) {
        @Override
        public String toString() {
            return "WbproPairingCommand[memberId=" + memberId + ", importRef=" + importRef + "]";
        }
    }
}
