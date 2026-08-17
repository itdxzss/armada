package com.armada.group.service.impl;

import com.armada.group.model.enums.GroupLinkPrecheckStatus;
import com.armada.group.model.vo.GroupLinkPrecheckItemVO;
import com.armada.group.model.vo.GroupLinkPrecheckResultVO;
import com.armada.group.service.GroupInvitePageFetcher;
import com.armada.group.service.GroupInvitePageMetadata;
import com.armada.group.service.GroupLinkPrecheckService;
import com.armada.group.service.GroupLinkUrls;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 基于 WhatsApp 公开邀请页的群链接导入前预检测实现。
 *
 * <p>本服务面向“用户还没正式导入”的阶段,因此只依赖 raw link 本身能访问到的公开邀请页。
 * 这里刻意不写任何群入口、群资料或健康事实，
 * 也不调用协议层 {@code batch-preview}:协议层预览需要在线账号和已入库的 group_link id,
 * 不适合作为导入弹窗里的轻量预检测第一步。</p>
 *
 * <p>当前可用性口径是产品导入前筛选口径:格式合法且公开页能识别出群名或真实头像即视为
 * {@code AVAILABLE};格式错误、页面抓取异常、页面只有 WhatsApp 默认资料则视为
 * {@code UNAVAILABLE}。后续接入协议层深度检测时,应作为另一层确认,不要把这里改成有账号依赖。</p>
 */
@Service
public class GroupLinkPrecheckServiceImpl implements GroupLinkPrecheckService {

    private static final Logger log = LoggerFactory.getLogger(GroupLinkPrecheckServiceImpl.class);

    private static final String NO_PROFILE_REASON = "未识别到群资料";
    private static final String FETCH_FAILED_REASON = "链接检测失败";

    private final GroupInvitePageFetcher invitePageFetcher;

    /**
     * 创建导入前预检测服务。
     *
     * @param invitePageFetcher WhatsApp 公开邀请页元数据抓取端口
     */
    public GroupLinkPrecheckServiceImpl(GroupInvitePageFetcher invitePageFetcher) {
        this.invitePageFetcher = invitePageFetcher;
    }

    /**
     * {@inheritDoc}
     *
     * <p>同一批里重复的归一化链接只抓取一次公开页,重复行复用第一次检测结果并保留自己的行号。</p>
     */
    @Override
    public GroupLinkPrecheckResultVO precheck(List<String> rawLinks) {
        // key 是归一化链接。LineImporter 会把批内重复行标成 DUPLICATE,这里用缓存复用首次检测结果,
        // 避免同一个公开页在一次预检测请求内被重复请求。
        Map<String, ProbeResult> detectedByUrl = new HashMap<>();

        // 复用导入引擎的逐行语义:保留物理行号、trim 后原文、空行跳过、格式错误落到单行结果。
        // Controller 后续可以从文本框/文件解析出 List<String>,Service 再拼回多行交给 LineImporter。
        String text = rawLinks == null ? "" : String.join("\n", rawLinks);
        List<LineOutcome<String, ProbeResult>> outcomes = LineImporter.run(
                text,
                // 与正式导入保持同一套归一化规则:行内可带序号/说明/查询串,最终抽取 22 位 invite code。
                GroupLinkUrls::normalizeImportLine,
                url -> url,
                url -> {
                    ProbeResult result = detect(url);
                    detectedByUrl.put(url, result);
                    return result;
                });

        List<GroupLinkPrecheckItemVO> items = new ArrayList<>(outcomes.size());
        int available = 0;
        for (LineOutcome<String, ProbeResult> outcome : outcomes) {
            // 所有 LineOutcome 都转成前端可直接渲染的行结果,包括格式错误和批内重复。
            GroupLinkPrecheckItemVO item = toItem(outcome, detectedByUrl);
            if (GroupLinkPrecheckStatus.AVAILABLE.code().equals(item.status())) {
                available++;
            }
            items.add(item);
        }
        int total = items.size();
        return new GroupLinkPrecheckResultVO(total, available, total - available, List.copyOf(items));
    }

    private GroupLinkPrecheckItemVO toItem(
            LineOutcome<String, ProbeResult> outcome,
            Map<String, ProbeResult> detectedByUrl) {
        if (outcome.kind() == Kind.FAILED) {
            // 解析阶段失败代表这行没有可检测的规范链接,不能请求公开页。
            return unavailableItem(outcome.lineNo(), outcome.raw(), null, null, outcome.reason());
        }

        // PERSISTED:本行是该归一化链接首次出现,检测结果在 persistResult。
        // DUPLICATE:本行是批内重复,LineImporter 不再执行 persist 回调,从 detectedByUrl 取首次结果。
        ProbeResult probe = outcome.kind() == Kind.DUPLICATE
                ? detectedByUrl.get(outcome.record())
                : outcome.persistResult();
        if (probe == null) {
            // 防御分支:正常情况下只有首次检测异常中断缓存写入才会走到这里。为保证单行可独立输出,
            // 允许补测一次,并把结果回填缓存供后续重复行复用。
            probe = detect(outcome.record());
            detectedByUrl.put(outcome.record(), probe);
        }
        return new GroupLinkPrecheckItemVO(
                outcome.lineNo(),
                outcome.raw(),
                outcome.record(),
                probe.inviteCode(),
                probe.groupName(),
                probe.avatarUrl(),
                probe.status().code(),
                probe.status().label(),
                probe.failReason());
    }

    private ProbeResult detect(String normalizedUrl) {
        GroupInvitePageMetadata metadata;
        try {
            // GroupInvitePageFetcher 内部只访问 chat.whatsapp.com 公开页面,解析 og:title / og:image。
            // 这一步不需要 WhatsApp 在线账号,适合在导入确认前同步返回给前端。
            metadata = invitePageFetcher.fetch(normalizedUrl);
        } catch (RuntimeException e) {
            // 公开页访问失败不能抛出打断整批预检测;单条标不可用即可,方便用户删除或稍后重试。
            log.warn("群链接预检测公开邀请页抓取失败 normalizedUrl={} error={}", normalizedUrl, e.getMessage());
            return new ProbeResult(
                    inviteCode(normalizedUrl),
                    null,
                    null,
                    GroupLinkPrecheckStatus.UNAVAILABLE,
                    FETCH_FAILED_REASON);
        }

        // fetcher 正常会从 normalizedUrl 提取 inviteCode;这里保留 fallback,防止后续替换 fetcher
        // 时返回了没有 inviteCode 的空 profile,导致前端无法把结果和原链接关联。
        String inviteCode = metadata == null || metadata.inviteCode() == null
                ? inviteCode(normalizedUrl)
                : metadata.inviteCode();
        if (metadata == null || !metadata.hasProfile()) {
            // 公开页只有 WhatsApp 默认标题/默认 logo 时,fetcher 会归一成空 profile。
            // 对导入前筛选而言,无法展示群名/头像就明确标不可用,而不是给一个模糊的未检测状态。
            return new ProbeResult(
                    inviteCode,
                    null,
                    null,
                    GroupLinkPrecheckStatus.UNAVAILABLE,
                    NO_PROFILE_REASON);
        }

        // 群名和头像任一可识别都足够支撑导入前列表展示;真实健康状态留给后续协议层检测确认。
        return new ProbeResult(
                inviteCode,
                metadata.waSubject(),
                metadata.avatarUrl(),
                GroupLinkPrecheckStatus.AVAILABLE,
                null);
    }

    private static GroupLinkPrecheckItemVO unavailableItem(
            int lineNo,
            String rawUrl,
            String normalizedUrl,
            String inviteCode,
            String failReason) {
        // 统一构造不可用行,保证格式错误/抓取失败/无 profile 三类失败给前端同一套字段。
        return new GroupLinkPrecheckItemVO(
                lineNo,
                rawUrl,
                normalizedUrl,
                inviteCode,
                null,
                null,
                GroupLinkPrecheckStatus.UNAVAILABLE.code(),
                GroupLinkPrecheckStatus.UNAVAILABLE.label(),
                failReason);
    }

    private static String inviteCode(String normalizedUrl) {
        // normalizedUrl 形如 chat.whatsapp.com/<code>,保留一个轻量解析避免为 fallback 引入 URI 解析。
        if (normalizedUrl == null || normalizedUrl.isBlank()) {
            return null;
        }
        int slash = normalizedUrl.lastIndexOf('/');
        if (slash < 0 || slash == normalizedUrl.length() - 1) {
            return null;
        }
        return normalizedUrl.substring(slash + 1);
    }

    /**
     * 内部检测结果。
     *
     * <p>先独立于 VO 保存,是为了让同一批重复链接复用同一次公开页检测结果,最后再按各自
     * lineNo/rawUrl 组装成前端行结果。</p>
     */
    private record ProbeResult(
            String inviteCode,
            String groupName,
            String avatarUrl,
            GroupLinkPrecheckStatus status,
            String failReason) {
    }
}
