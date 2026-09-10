package com.armada.marketing.script.service;

import com.armada.marketing.model.vo.ScriptQualificationVO;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 启动被资格检查拦截，响应携带逐群缺口而非仅通用错误文本。 */
public class ScriptQualificationException extends BusinessException {
    private final ScriptQualificationVO report;
    /** 保存完整检查报告，不包含任何发送副作用。 */
    public ScriptQualificationException(ScriptQualificationVO report) {
        super(ErrorCode.CONFLICT, "所选群暂不满足启动条件，请查看缺口并处理后重新检查");
        this.report = report;
    }
    /** 读取可持续展示的逐群报告。 */
    public ScriptQualificationVO getReport() { return report; }
}
