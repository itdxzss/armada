package com.armada.shared.util;

/**
 * 逐行导入：单行解析/校验不合格。由行解析器抛出，{@link LineImporter} 捕获并计入失败行 + 记录原因，不中断其它行。
 *
 * <p>仅作导入骨架内部控制流，不面向前端（导入整体仍返回 200 + 统计结果）。</p>
 */
public class ImportLineException extends RuntimeException {

    public ImportLineException(String reason) {
        super(reason);
    }
}
