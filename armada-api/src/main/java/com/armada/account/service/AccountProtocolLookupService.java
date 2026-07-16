package com.armada.account.service;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import java.util.List;

/**
 * 向任务域提供发送协议命令所需的最小账号身份。
 *
 * <p>进群调度不能直接依赖账号 Mapper，否则会绕过账号域对软删除、租户隔离和协议身份完整性的
 * 统一判断。本服务作为跨域边界，只暴露协议路由所需字段，不把账号实体泄露给任务域。</p>
 */
public interface AccountProtocolLookupService {

    /**
     * 按调用顺序解析当前租户下可用于协议命令的账号引用。
     *
     * <p>不存在、已软删除或缺少协议标识、协议账号 ID、手机号的账号不会出现在结果中；调用方据此
     * 将对应任务明细收敛为账号不可用，而不是构造无法路由的 Kafka 命令。</p>
     *
     * @param accountIds Armada 账号 ID；允许包含空值和重复值
     * @return 去重后仍保持首次出现顺序的有效协议账号引用；无有效输入时返回空列表
     */
    List<ProtocolAccountRef> findActiveProtocolRefs(List<Long> accountIds);
}
