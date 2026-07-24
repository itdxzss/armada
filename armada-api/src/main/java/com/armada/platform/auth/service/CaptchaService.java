package com.armada.platform.auth.service;

import com.armada.platform.auth.model.CaptchaChallenge;

/** 一次性图片验证码服务。 */
public interface CaptchaService {

    /** 生成验证码图片并把答案限时写入 Redis。 */
    CaptchaChallenge create();

    /** 一次性消费并校验验证码，成功返回 true，错误或过期返回 false。 */
    boolean consume(String captchaId, String answer);
}
