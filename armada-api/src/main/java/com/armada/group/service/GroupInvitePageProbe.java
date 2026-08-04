package com.armada.group.service;

/**
 * 公开邀请页抓取结果，区分"页面不可达"与"页面可达但无群资料"。
 *
 * <p>两者对业务的含义完全不同：不可达是本系统侧的网络问题，链接可能仍然有效；
 * 可达但无群资料说明链接已被撤销或群已删除。调用方必须能分开处理，
 * 否则会把自身网络抖动当成用户链接失效。</p>
 *
 * @param metadata  页面可识别出的群资料；不可达时为空 profile
 * @param reachable 页面是否成功返回 2xx 并完成解析
 */
public record GroupInvitePageProbe(GroupInvitePageMetadata metadata, boolean reachable) {
}
