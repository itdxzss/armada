package com.armada.task.service;

import com.armada.group.service.GroupLinkUrls;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.armada.shared.util.LineImporter;
import com.armada.shared.util.LineImporter.Kind;
import com.armada.shared.util.LineImporter.LineOutcome;
import com.armada.task.model.enums.PullTaskStandardLinkLineStatus;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 普通群链接创建页的粘贴文本判定服务。
 *
 * <p>只做逐行归一化、批内去重和占用比对，不写库、不访问 WhatsApp 公开邀请页、
 * 不调协议层。链接是否真实可用由任务执行时管理员账号实际进群结果判定。</p>
 */
@Service
public class PullTaskLinkProbeService {

    /** 单次粘贴允许的最大唯一有效链接数。 */
    public static final int MAX_VALID_LINK_COUNT = 200;

    /** 链接已被其他任务占用时的失败原因。 */
    private static final String OCCUPIED_REASON = "该链接已被其他任务占用";

    /**
     * 判定一段粘贴文本。
     *
     * @param linksText     创建页链接框的全量文本；null 或空白返回空结果
     * @param occupiedLinks 已被本租户其他运行中任务占用的归一化链接
     * @return 逐行结果与进入匹配池的链接
     * @throws BusinessException 唯一有效链接数超过 {@link #MAX_VALID_LINK_COUNT} 时
     */
    public ProbeResult probe(String linksText, Set<String> occupiedLinks) {
        List<LineOutcome<String, String>> outcomes = parseLines(linksText);

        Set<String> candidates = new LinkedHashSet<>();
        for (LineOutcome<String, String> outcome : outcomes) {
            if (outcome.kind() == Kind.PERSISTED && !occupiedLinks.contains(outcome.record())) {
                candidates.add(outcome.record());
            }
        }
        if (candidates.size() > MAX_VALID_LINK_COUNT) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "单次最多粘贴 " + MAX_VALID_LINK_COUNT + " 条有效链接，请分批提交");
        }

        return buildResult(outcomes, occupiedLinks);
    }

    /**
     * 只做归一化与批内去重，不判占用。
     *
     * <p>调用方需要先拿归一化链接去查占用，再把占用集合传回 {@link #probe(String, Set)}。
     * 归一化必须统一走 {@code GroupLinkUrls.normalizeImportLine}——它能从带序号、说明文字和
     * 查询串的运营文本里抽出邀请链接；换成整行严格匹配的 {@code tryNormalize} 会漏查占用。</p>
     *
     * @param linksText 创建页链接框的全量文本
     * @return 按首次出现顺序去重的归一化链接
     */
    public static Set<String> candidateLinks(String linksText) {
        Set<String> candidates = new LinkedHashSet<>();
        for (LineOutcome<String, String> outcome : parseLines(linksText)) {
            if (outcome.kind() == Kind.PERSISTED) {
                candidates.add(outcome.record());
            }
        }
        return candidates;
    }

    /**
     * 逐行归一化并标出格式失败与批内重复。
     *
     * @param linksText 创建页链接框的全量文本
     * @return 逐行产出
     */
    private static List<LineOutcome<String, String>> parseLines(String linksText) {
        return LineImporter.run(
                linksText, GroupLinkUrls::normalizeImportLine, url -> url, url -> url);
    }

    /**
     * 按原始行顺序组装逐行结果，并收集进入匹配池的链接。
     *
     * @param outcomes      逐行产出
     * @param occupiedLinks 已占用链接
     * @return 逐行结果与匹配池
     */
    private static ProbeResult buildResult(List<LineOutcome<String, String>> outcomes,
                                           Set<String> occupiedLinks) {
        List<LinkLine> lines = new ArrayList<>(outcomes.size());
        List<String> poolLinks = new ArrayList<>();
        for (LineOutcome<String, String> outcome : outcomes) {
            lines.add(toLine(outcome, occupiedLinks, poolLinks));
        }
        return new ProbeResult(List.copyOf(lines), List.copyOf(poolLinks));
    }

    /**
     * 判定单行终态；命中匹配池条件时顺带写入 {@code poolLinks}。
     *
     * @param outcome       单行产出
     * @param occupiedLinks 已占用链接
     * @param poolLinks     匹配池收集器
     * @return 单行结果
     */
    private static LinkLine toLine(LineOutcome<String, String> outcome,
                                   Set<String> occupiedLinks,
                                   List<String> poolLinks) {
        if (outcome.kind() == Kind.FAILED) {
            return new LinkLine(outcome.lineNo(), outcome.raw(), null,
                    PullTaskStandardLinkLineStatus.INVALID_FORMAT, outcome.reason());
        }
        String link = outcome.record();
        if (outcome.kind() == Kind.DUPLICATE) {
            return new LinkLine(outcome.lineNo(), outcome.raw(), link,
                    PullTaskStandardLinkLineStatus.DUPLICATE, null);
        }
        if (occupiedLinks.contains(link)) {
            return new LinkLine(outcome.lineNo(), outcome.raw(), link,
                    PullTaskStandardLinkLineStatus.OCCUPIED, OCCUPIED_REASON);
        }
        poolLinks.add(link);
        return new LinkLine(outcome.lineNo(), outcome.raw(), link,
                PullTaskStandardLinkLineStatus.VALID, null);
    }

    /**
     * 粘贴文本中的单行判定结果。
     *
     * @param lineNo         原始物理行号
     * @param raw            trim 后的行原文
     * @param normalizedLink 归一化链接；格式非法时为 null
     * @param status         终态
     * @param reason         失败或提示原因；无需提示时为 null
     */
    public record LinkLine(int lineNo, String raw, String normalizedLink,
                           PullTaskStandardLinkLineStatus status, String reason) {
    }

    /**
     * 一次判定的完整产出。
     *
     * @param lines     按原始行顺序的逐行结果
     * @param poolLinks 进入随机匹配池的链接，按首次出现顺序
     */
    public record ProbeResult(List<LinkLine> lines, List<String> poolLinks) {
    }
}
