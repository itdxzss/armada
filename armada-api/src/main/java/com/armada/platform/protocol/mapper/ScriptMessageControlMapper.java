package com.armada.platform.protocol.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原命令 CAS 控制 SQL，租户条件由插件注入，聚合类型限制为剧本。 */
@Mapper
public interface ScriptMessageControlMapper {
    /** 在 publisher 提交发送权之前持有命令。 */ int hold(@Param("commandId") String commandId, @Param("now") long now);
    /** 只恢复带暂停标记的同一命令。 */ int resume(@Param("commandId") String commandId, @Param("now") long now);
    /** 取消尚未投递的命令。 */ int expirePending(@Param("commandId") String commandId, @Param("now") long now);
    /** 发送权已提交时禁止失败重试，保留原发送结果。 */ int expireDispatching(@Param("commandId") String commandId, @Param("now") long now);
}
