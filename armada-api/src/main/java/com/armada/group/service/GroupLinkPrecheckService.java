package com.armada.group.service;

import com.armada.group.model.vo.GroupLinkPrecheckResultVO;
import java.util.List;

/**
 * 群链接导入前预检测服务。
 */
public interface GroupLinkPrecheckService {

    /**
     * 对一批原始群邀请链接执行导入前预检测。
     *
     * <p>本服务只做格式归一化与 WhatsApp 公开邀请页元数据抓取,不入库、不调用协议层账号能力。</p>
     *
     * @param rawLinks 原始行列表;空行会被忽略
     * @return 预检测汇总和逐行结果
     */
    GroupLinkPrecheckResultVO precheck(List<String> rawLinks);
}
