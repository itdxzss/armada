package com.armada.hyperlink.strategy.converter;

import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyCreateDTO;
import com.armada.hyperlink.strategy.model.dto.HyperlinkStrategyUpdateDTO;
import com.armada.hyperlink.strategy.model.entity.HyperlinkStrategy;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyDetailVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyListItemVO;
import com.armada.hyperlink.strategy.model.vo.HyperlinkStrategyOptionVO;
import com.armada.hyperlink.task.model.dto.HyperlinkAccountFilterDTO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** 超链策略请求、数据库实体与响应对象转换器。 */
@Mapper(componentModel = "spring")
public interface HyperlinkStrategyConverter {

    /** 把已归一化的创建请求转换为待插入实体。 */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "strategyName", source = "request.name")
    @Mapping(target = "taskType", source = "taskType")
    @Mapping(target = "accountFilter", source = "accountFilterJson")
    @Mapping(target = "concurrentNum", source = "request.maxExecutingAccounts")
    @Mapping(target = "maxUseAccount", source = "request.maxUseAccounts")
    @Mapping(target = "accountMaxSendNum", source = "request.maxSendPerAccount")
    @Mapping(target = "taskIntervalMinutes", source = "request.cycleIntervalMinutes")
    @Mapping(target = "enabled", source = "request.enabled")
    HyperlinkStrategy toEntity(
            HyperlinkStrategyCreateDTO request, Integer taskType, String accountFilterJson);

    /** 把已归一化的更新请求转换为待更新实体。 */
    @BeanMapping(ignoreByDefault = true)
    @Mapping(target = "strategyName", source = "request.name")
    @Mapping(target = "taskType", source = "taskType")
    @Mapping(target = "accountFilter", source = "accountFilterJson")
    @Mapping(target = "concurrentNum", source = "request.maxExecutingAccounts")
    @Mapping(target = "maxUseAccount", source = "request.maxUseAccounts")
    @Mapping(target = "accountMaxSendNum", source = "request.maxSendPerAccount")
    @Mapping(target = "taskIntervalMinutes", source = "request.cycleIntervalMinutes")
    @Mapping(target = "enabled", source = "request.enabled")
    HyperlinkStrategy toEntity(
            HyperlinkStrategyUpdateDTO request, Integer taskType, String accountFilterJson);

    /** 转换完整详情，数据库别名不进入 wire。 */
    @Mapping(target = "name", source = "entity.strategyName")
    @Mapping(target = "taskMode", source = "taskMode")
    @Mapping(target = "accountFilter", source = "accountFilter")
    @Mapping(target = "maxExecutingAccounts", source = "entity.concurrentNum")
    @Mapping(target = "maxUseAccounts", source = "entity.maxUseAccount")
    @Mapping(target = "maxSendPerAccount", source = "entity.accountMaxSendNum")
    @Mapping(target = "cycleIntervalMinutes", source = "entity.taskIntervalMinutes")
    HyperlinkStrategyDetailVO toDetail(
            HyperlinkStrategy entity, String taskMode, HyperlinkAccountFilterDTO accountFilter);

    /** 转换分页列表项。 */
    @Mapping(target = "name", source = "entity.strategyName")
    @Mapping(target = "taskMode", source = "taskMode")
    @Mapping(target = "accountFilter", source = "accountFilter")
    @Mapping(target = "maxExecutingAccounts", source = "entity.concurrentNum")
    @Mapping(target = "maxUseAccounts", source = "entity.maxUseAccount")
    @Mapping(target = "maxSendPerAccount", source = "entity.accountMaxSendNum")
    @Mapping(target = "cycleIntervalMinutes", source = "entity.taskIntervalMinutes")
    HyperlinkStrategyListItemVO toListItem(
            HyperlinkStrategy entity, String taskMode, HyperlinkAccountFilterDTO accountFilter);

    /** 转换任务选择器使用的弱引用候选。 */
    @Mapping(target = "name", source = "entity.strategyName")
    @Mapping(target = "taskMode", source = "taskMode")
    @Mapping(target = "accountFilter", source = "accountFilter")
    @Mapping(target = "maxExecutingAccounts", source = "entity.concurrentNum")
    @Mapping(target = "maxUseAccounts", source = "entity.maxUseAccount")
    @Mapping(target = "maxSendPerAccount", source = "entity.accountMaxSendNum")
    @Mapping(target = "cycleIntervalMinutes", source = "entity.taskIntervalMinutes")
    HyperlinkStrategyOptionVO toOption(
            HyperlinkStrategy entity, String taskMode, HyperlinkAccountFilterDTO accountFilter);
}
