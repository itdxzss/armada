package com.armada.account.converter;

import com.armada.account.model.vo.AccountMessageCapVO;
import com.armada.platform.protocol.model.result.ProtocolMessageCap;
import org.mapstruct.Mapper;

/** 协议层额度结果到对外 VO 的编译期映射，字段一一对应、不做任何推断。 */
@Mapper(componentModel = "spring")
public interface AccountMessageCapConverter {

    /** 协议层额度结果转为账号域 VO。 */
    AccountMessageCapVO toVO(ProtocolMessageCap cap);
}
