package com.armada.platform.auth.service;

import com.armada.platform.auth.model.CaptchaChallenge;

/** 一次性图片验证码服务。 */
public interface CaptchaService {

    /**
     * 生成验证码图片并把答案限时写入 Redis。
     *
     * @return 验证码标识、Base64 图片和有效秒数
     * @throws com.armada.platform.auth.exception.AuthInfrastructureException Redis 或图片生成不可用时抛出
     */
    CaptchaChallenge create();

    /**
     * 一次性消费并校验验证码，无论答案是否正确都不能再次使用。
     *
     * @param captchaId 验证码标识
     * @param answer 用户输入的验证码答案
     * @return 匹配时为 true，参数错误、答案错误或验证码过期时为 false
     * @throws com.armada.platform.auth.exception.AuthInfrastructureException Redis 不可用时抛出
     */
    boolean consume(String captchaId, String answer);
}
