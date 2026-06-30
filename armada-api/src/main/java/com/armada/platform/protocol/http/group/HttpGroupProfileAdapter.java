package com.armada.platform.protocol.http.group;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.armada.platform.protocol.http.ProtocolHttpExecutor;
import com.armada.platform.protocol.port.GroupProfilePort;
import java.util.Map;

/**
 * {@link GroupProfilePort} 的 HTTP adapter。
 *
 * <p>对应协议层群资料修改接口,baseUrl 指向 master 时由协议层按 accountId 路由 owner worker。
 * 本类只做防腐层 wire 适配:校验必填字段、组装协议层 JSON body、把 HTTP 错误交给
 * {@link ProtocolHttpExecutor} 统一映射。</p>
 */
public class HttpGroupProfileAdapter implements GroupProfilePort {

    private static final String SUBJECT_URI_TEMPLATE = "/v1/groups/%s/subject";
    private static final String DESCRIPTION_URI_TEMPLATE = "/v1/groups/%s/description";
    private static final String ANNOUNCEMENT_TEXT_URI_TEMPLATE = "/v1/groups/%s/announcement-text";
    private static final String PICTURE_URI_TEMPLATE = "/v1/groups/%s/picture";

    private final ProtocolHttpExecutor httpExecutor;

    public HttpGroupProfileAdapter(ProtocolHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    /**
     * 调用协议层 {@code POST /v1/groups/:groupJid/subject}。
     *
     * <p>subject 是 WhatsApp 真实群名称,协议层要求非空字符串;业务层已做长度校验,
     * adapter 这里仍保留非空校验,防止端口被其它调用方绕过业务层直接使用。</p>
     */
    @Override
    public void updateSubject(String protocolAccountId, String groupJid, String subject) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String jid = requireText(groupJid, "groupJid");
        String value = requireText(subject, "subject");
        httpExecutor.postVoid(SUBJECT_URI_TEMPLATE.formatted(jid), new SubjectRequest(accountId, value));
    }

    /**
     * 调用协议层 {@code POST /v1/groups/:groupJid/description}。
     *
     * <p>description 允许为 {@code null},协议层会把 null 转成 Baileys 的 undefined,表达清空群描述。
     * 因此本方法只校验 accountId 和 groupJid,不对 description 做 requireText。</p>
     */
    @Override
    public void updateDescription(String protocolAccountId, String groupJid, String description) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String jid = requireText(groupJid, "groupJid");
        httpExecutor.postVoid(DESCRIPTION_URI_TEMPLATE.formatted(jid), new DescriptionRequest(accountId, description));
    }

    /**
     * 调用协议层 {@code POST /v1/groups/:groupJid/announcement-text}。
     *
     * <p>协议层当前把 announcement-text 应用为群 description,但 wire 契约仍使用 {@code text} 字段。
     * 这里不改名成 description,避免 Armada 防腐层和协议层接口契约错位。</p>
     */
    @Override
    public void updateAnnouncementText(String protocolAccountId, String groupJid, String text) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String jid = requireText(groupJid, "groupJid");
        String value = requireText(text, "text");
        httpExecutor.postVoid(ANNOUNCEMENT_TEXT_URI_TEMPLATE.formatted(jid),
                new AnnouncementTextRequest(accountId, value));
    }

    /**
     * 调用协议层 {@code POST /v1/groups/:groupJid/picture}。
     *
     * <p>协议层 schema 是 {@code image: { url?: string, base64?: string }},可选字段不能传 null。
     * 因此图片 body 不能用固定 record 同时带 url/base64,必须按实际输入构造只含一个键的 map。</p>
     */
    @Override
    public void updatePicture(String protocolAccountId, String groupJid, String url, String base64) {
        String accountId = requireText(protocolAccountId, "protocolAccountId");
        String jid = requireText(groupJid, "groupJid");
        Map<String, String> image = imagePayload(url, base64);
        httpExecutor.postVoid(PICTURE_URI_TEMPLATE.formatted(jid),
                new PictureRequest(accountId, image));
    }

    /** 必填协议字段统一 trim;缺失时抛 ProtocolException,由上层按协议调用失败处理。 */
    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group profile 参数缺失 " + fieldName);
        }
        return value.trim();
    }

    /** 可选协议字段统一把空白折叠为 null,便于后续做二选一判断。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * 构造协议层 picture.image 对象。
     *
     * <p>必须返回单字段 map:URL 形态只包含 {@code url},base64 形态只包含 {@code base64}。
     * 这样 Jackson 不会序列化出 {@code url:null}/{@code base64:null},避免协议层 zod optional 校验失败。</p>
     */
    private static Map<String, String> imagePayload(String url, String base64) {
        String normalizedUrl = blankToNull(url);
        String normalizedBase64 = blankToNull(base64);
        if ((normalizedUrl == null && normalizedBase64 == null)
                || (normalizedUrl != null && normalizedBase64 != null)) {
            throw new ProtocolException(ProtocolErrorCode.UNKNOWN, "协议层 group picture 参数必须 url/base64 二选一");
        }
        return normalizedUrl == null
                ? Map.of("base64", normalizedBase64)
                : Map.of("url", normalizedUrl);
    }

    private record SubjectRequest(String accountId, String subject) {
    }

    private record DescriptionRequest(String accountId, String description) {
    }

    private record AnnouncementTextRequest(String accountId, String text) {
    }

    private record PictureRequest(String accountId, Map<String, String> image) {
    }
}
