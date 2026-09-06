package com.armada.promotion.pairing.model.enums;

import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;

/** 共用配对会话表中的业务场景。 */
public enum PromotionPairingScene {
    PROMOTION(1),
    CONTROL_ACCOUNT_IMPORT(2);

    private final int code;

    PromotionPairingScene(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static PromotionPairingScene fromCode(Integer code) {
        if (code == null) {
            return PROMOTION;
        }
        for (PromotionPairingScene scene : values()) {
            if (Integer.valueOf(scene.code).equals(code)) {
                return scene;
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "未知配对会话场景");
    }
}
