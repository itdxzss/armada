package com.armada.marketing.asset.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 新增图片的业务归属；历史图片在数据库中为 NULL，不能由上传接口指定为历史。 */
public enum ResourceAssetScope {
    /** 超链图片素材。 */
    HYPERLINK(1),
    /** 养群图片素材。 */
    SCRIPT(2),
    /** 既有普通营销模板上传。 */
    MARKETING(3);

    /** 持久化业务码。 */
    private final int code;
    ResourceAssetScope(int code) { this.code = code; }
    /** @return 数据库业务码 */
    public int getCode() { return code; }
    /** @param storedScope 图片归属，NULL 为历史共享；越界时拒绝读取或绑定 */
    public void requireVisible(Integer storedScope) {
        if (storedScope != null && storedScope != code) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "图片不属于当前业务素材库");
        }
    }
}
