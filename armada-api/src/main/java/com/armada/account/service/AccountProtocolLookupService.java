package com.armada.account.service;

import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 向任务域提供发送协议命令所需的最小账号身份。
 *
 * <p>进群调度不能直接依赖账号 Mapper，否则会绕过账号域对软删除、租户隔离和协议身份完整性的
 * 统一判断。本服务作为跨域边界，只暴露协议路由所需字段，不把账号实体泄露给任务域。</p>
 */
public interface AccountProtocolLookupService {

    /**
     * 查询当前租户指定活跃账号的完整协议引用。
     *
     * <p>固定账号执行只校验租户、软删除和协议寻址事实；离线账号仍可返回，由具体业务决定是否
     * 发起上线或执行命令。本查询不预检群成员、群角色、禁言等运行时事实。</p>
     *
     * <p>存量 Web 账号允许 {@code protocol_id} 为空，此时沿用平台统一规则按 Web 路由；只有手机号或
     * 协议账号句柄缺失才属于协议身份不完整。</p>
     *
     * @param accountId Armada 账号 ID
     * @return 完整协议引用；账号不可见、已软删或手机号/协议账号句柄缺失时为空
     */
    Optional<ProtocolAccountRef> findActiveProtocolRef(Long accountId);

    /**
     * 从当前租户指定账号分组随机选择一个在线正常拉手账号。
     *
     * <p>候选必须协议身份完整、在线、生命周期正常、风险允许且未禁言；选号不关联任务占用表，
     * 避免账号被某类任务登记占用后错误排除其它合法调度。</p>
     *
     * @param groupId 账号分组 ID
     * @return 随机可用协议引用；无候选时为空
     */
    Optional<ProtocolAccountRef> findRandomOnlineNormalByGroupId(Long groupId);

    /**
     * 查询指定分组内全部在线正常账号,用于用户显式触发的群列表同步。
     *
     * @param groupId 账号组 ID
     * @return 按账号 ID 排序的协议引用
     */
    List<ProtocolAccountRef> findOnlineNormalByGroupId(Long groupId);

    /**
     * 从当前租户指定分组随机选择一个在线正常的 Web 拉手账号。
     *
     * <p>该专用查询服务于联系人保存和成员 ADD 尚只支持 Web 的执行链路，不改变通用选号语义。</p>
     *
     * @param groupId 账号分组 ID
     * @return 随机 Web 拉手；无候选时为空
     */
    Optional<ProtocolAccountRef> findRandomOnlineNormalWebByGroupId(Long groupId);

    /**
     * 按完整 WA 号码批量查询当前租户活跃账号的协议引用。
     *
     * <p>输入会去除首尾空白、空值和重复值。查询不按在线状态、群成员、群角色或禁言状态过滤，
     * 仅排除不可见、软删除和协议身份不完整账号。</p>
     *
     * @param phones 完整 WA 号码；允许空值、空白和重复值
     * @return 以规范化号码为键、保持首次出现顺序的协议引用映射
     */
    Map<String, ProtocolAccountRef> findActiveProtocolRefsByPhones(List<String> phones);

    /**
     * 按调用顺序解析当前租户下可用于协议命令的账号引用。
     *
     * <p>不存在、已软删除或缺少协议账号 ID、手机号的账号不会出现在结果中；存量 Web 账号的空
     * {@code protocol_id} 按 Web 路由。调用方据此将真正不可寻址的任务明细收敛为账号不可用，
     * 而不是构造无法路由的 Kafka 命令。</p>
     *
     * @param accountIds Armada 账号 ID；允许包含空值和重复值
     * @return 去重后仍保持首次出现顺序的有效协议账号引用；无有效输入时返回空列表
     */
    List<ProtocolAccountRef> findActiveProtocolRefs(List<Long> accountIds);
}
