package com.armada.platform.protocol.backend.android;

import com.armada.platform.kafka.config.ProtocolAndroidCommandProperties;
import com.armada.platform.protocol.media.AndroidImageAsset;
import com.armada.platform.protocol.media.AndroidImageAssetRef;
import com.armada.platform.protocol.media.AndroidImageAssetStore;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.platform.protocol.model.command.ProtocolMessageOutboxCommand;
import com.armada.platform.protocol.model.enums.MessageType;
import com.armada.platform.protocol.model.enums.ProtocolBackend;
import com.armada.platform.protocol.model.result.MessageSendEnqueueItem;
import com.armada.platform.protocol.model.result.MessageSendEnqueueResult;
import com.armada.platform.protocol.routing.MessageSendBackend;
import com.armada.platform.protocol.service.ProtocolCommandOutboxService;
import com.armada.shared.util.HttpUrlValidator;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android Zhuan 协议营销消息 backend。
 *
 * <p>协议差异和能力校验只存在于该 adapter。Android 按钮卡片必须恰好包含一个有效
 * HTTP(S) 跳转按钮，非法命令在 Armada 本地拒绝且不写 Android outbox。</p>
 *
 * <p>本类不直接调用 Android Zhuan 发送消息，而是把协议无关的 {@link MessageSendCommand}
 * 转成 Android Kafka wire payload，再交给统一 outbox 落库。返回 accepted 仅表示命令已进入
 * 本地 outbox；最终发送成功与否由 Android 结果事件异步回写。</p>
 */
public final class AndroidMessageSendBackend implements MessageSendBackend {

    /** 组件日志。 */
    private static final Logger log = LoggerFactory.getLogger(AndroidMessageSendBackend.class);

    /** Android 按钮能力校验失败时返回给营销域的稳定原因码。 */
    private static final String INVALID_BUTTON_CONFIG = "INVALID_ANDROID_BUTTON_CONFIG";

    /** 统一协议命令 outbox，只负责持久化 backend 已编码的 envelope。 */
    private final ProtocolCommandOutboxService outboxService;

    /** Android 命令 topic 配置。 */
    private final ProtocolAndroidCommandProperties properties;

    /** Android 营销图片共享 Redis 缓存。 */
    private final AndroidImageAssetStore assetStore;

    /**
     * 创建 Android 营销消息 backend。
     *
     * @param outboxService 协议命令 outbox 服务
     * @param properties Android 命令 topic 配置
     * @param assetStore Android 营销图片共享 Redis 缓存
     */
    public AndroidMessageSendBackend(
            ProtocolCommandOutboxService outboxService,
            ProtocolAndroidCommandProperties properties,
            AndroidImageAssetStore assetStore) {
        this.outboxService = outboxService;
        this.properties = properties;
        this.assetStore = assetStore;
    }

    /** 返回该实现负责的协议后端类型，供统一路由注册和分组。 */
    @Override
    public ProtocolBackend backend() {
        return ProtocolBackend.ANDROID;
    }

    /**
     * 校验并批量接受 Android 消息命令。
     *
     * <p>按钮配置错误按单条命令本地拒绝，其余命令仍可组成批次写入 outbox；返回结果按照输入顺序
     * 重新组装，调用方可据此准确回写对应 attempt/item。</p>
     *
     * @param commands 已由营销域组装的协议无关消息命令
     * @return 与输入顺序一一对应的本地接受或拒绝结果
     */
    @Override
    public MessageSendEnqueueResult enqueue(List<MessageSendCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return new MessageSendEnqueueResult(List.of());
        }
        List<MessageSendCommand> acceptedBusinessCommands = new ArrayList<>(commands.size());
        Map<String, MessageSendEnqueueItem> results = new HashMap<>(commands.size());
        for (MessageSendCommand command : commands) {
            MessageSendEnqueueItem validation = validateButtonCard(command);
            if (validation != null) {
                results.put(command.commandId(), validation);
                continue;
            }
            acceptedBusinessCommands.add(command);
            results.put(command.commandId(), MessageSendEnqueueItem.accepted(command.commandId()));
        }
        ResolvedMediaRegistry mediaRegistry = resolveMedia(acceptedBusinessCommands);
        List<ProtocolMessageOutboxCommand> acceptedCommands =
                new ArrayList<>(acceptedBusinessCommands.size());
        for (MessageSendCommand command : acceptedBusinessCommands) {
            acceptedCommands.add(toOutboxCommand(command, mediaRegistry));
        }
        if (!acceptedCommands.isEmpty()) {
            outboxService.enqueueMessageCommands(acceptedCommands);
        }
        return new MessageSendEnqueueResult(commands.stream()
                .map(command -> results.get(command.commandId()))
                .toList());
    }

    /**
     * 执行 Android 专属按钮能力校验。
     *
     * <p>非按钮消息无需在此校验；按钮消息只允许一个带非空文案的 HTTP(S) 跳转按钮。
     * 返回 null 表示可继续编码，不代表消息已经发送。</p>
     */
    private static MessageSendEnqueueItem validateButtonCard(MessageSendCommand command) {
        if (command.payload().type() != MessageType.BUTTON_CARD) {
            return null;
        }
        MessageSendCommand.MessageButtonCard card = command.payload().content().buttonCard();
        if (card == null || card.buttons() == null || card.buttons().size() != 1) {
            return rejected(command, "按钮数量只支持 1 个");
        }
        MessageSendCommand.MessageButton button = card.buttons().get(0);
        if (button == null || !"link".equalsIgnoreCase(button.type())) {
            return rejected(command, "只支持跳转链接按钮");
        }
        if (button.displayText() == null || button.displayText().isBlank()) {
            return rejected(command, "按钮显示文字不能为空");
        }
        if (!HttpUrlValidator.isHttpUrl(button.value())) {
            return rejected(command, "只接受有效的 HTTP(S) 跳转链接");
        }
        return null;
    }

    /** 构造单条 Android 本地拒绝结果，保持 commandId 与输入命令关联。 */
    private static MessageSendEnqueueItem rejected(MessageSendCommand command, String message) {
        return MessageSendEnqueueItem.rejected(command.commandId(), INVALID_BUTTON_CONFIG, message);
    }

    /**
     * 按租户和原图内容解析本批次全部媒体，并在 outbox 落库前确保 Redis 可读。
     *
     * <p>相同 byte[] 只计算一次摘要；内容相同但数组不同的媒体仍会按 tenantId + SHA-256
     * 合并，避免模板图片随群组数重复写缓存。</p>
     */
    private ResolvedMediaRegistry resolveMedia(List<MessageSendCommand> commands) {
        Map<Long, IdentityHashMap<byte[], AndroidImageAsset>> assetsBySource = new HashMap<>();
        Map<String, AndroidImageAsset> uniqueAssets = new LinkedHashMap<>();
        Map<Long, IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef>> references =
                new HashMap<>();
        int referenceCount = 0;
        for (MessageSendCommand command : commands) {
            Long tenantId = command.correlation().tenantId();
            MessageSendCommand.MessageContent content = command.payload().content();
            referenceCount += registerMedia(
                    tenantId, content.image(), assetsBySource, uniqueAssets, references);
            if (content.linkCard() != null) {
                referenceCount += registerMedia(
                        tenantId,
                        content.linkCard().thumbnail(),
                        assetsBySource,
                        uniqueAssets,
                        references);
            }
            if (content.buttonCard() != null) {
                referenceCount += registerMedia(
                        tenantId,
                        content.buttonCard().thumbnail(),
                        assetsBySource,
                        uniqueAssets,
                        references);
            }
        }
        uniqueAssets.values().forEach(assetStore::ensure);
        log.debug(
                "Android image batch resolved commandCount={} referenceCount={} uniqueAssetCount={}",
                commands.size(),
                referenceCount,
                uniqueAssets.size());
        return new ResolvedMediaRegistry(references);
    }

    private static int registerMedia(
            Long tenantId,
            MessageSendCommand.MessageMedia media,
            Map<Long, IdentityHashMap<byte[], AndroidImageAsset>> assetsBySource,
            Map<String, AndroidImageAsset> uniqueAssets,
            Map<Long, IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef>> references) {
        if (media == null) {
            return 0;
        }
        IdentityHashMap<byte[], AndroidImageAsset> tenantAssets =
                assetsBySource.computeIfAbsent(tenantId, ignored -> new IdentityHashMap<>());
        AndroidImageAsset asset = tenantAssets.computeIfAbsent(
                media.bytes(),
                source -> AndroidImageAsset.from(tenantId, source, media.mimetype()));
        uniqueAssets.putIfAbsent(asset.identity(), asset);
        references.computeIfAbsent(tenantId, ignored -> new IdentityHashMap<>())
                .put(media, asset.reference());
        return 1;
    }

    /**
     * 把统一命令编码成 Android Kafka payload 和 outbox 路由信息。
     *
     * <p>{@code wsPhone} 是 Android 在线实例解析所需事实，只存在于 Android payload；普通营销和
     * 建群营销、普通营销和历史群营销的关联字段按实际 source 三选一写入，避免业务关联串线。</p>
     */
    private ProtocolMessageOutboxCommand toOutboxCommand(
            MessageSendCommand command,
            ResolvedMediaRegistry mediaRegistry) {
        MessageSendCommand.MessageCorrelation correlation = command.correlation();
        MessageSendCommand.MarketingCorrelation marketing = correlation.marketing();
        MessageSendCommand.GroupCreationCorrelation groupCreation = correlation.groupCreation();
        MessageSendCommand.HistoricalGroupCorrelation historicalGroup = correlation.historicalGroup();
        MessageSendCommand.ContactTaskCorrelation contactTask = correlation.contactTask();
        MessageSendCommand.FeedTaskCorrelation feedTask = correlation.feedTask();
        MessageSendCommand.HyperlinkCorrelation hyperlink = correlation.hyperlink();
        MessageSendCommand.MessageContent content = command.payload().content();
        AndroidMessagePayload payload = new AndroidMessagePayload(
                correlation.tenantId(),
                command.account().armadaAccountId(),
                command.account().protocolAccountId(),
                command.account().wsPhone(),
                command.target().jid(),
                command.target().kind().name(),
                command.target().kind() == MessageSendCommand.TargetKind.GROUP
                        ? command.target().jid() : null,
                command.payload().type().name(),
                content.text(),
                media(correlation.tenantId(), content.image(), mediaRegistry),
                linkCard(correlation.tenantId(), content.linkCard(), mediaRegistry),
                buttonCard(correlation.tenantId(), content.buttonCard(), mediaRegistry),
                command.target().statusJidList(),
                content.backgroundColor(),
                content.textColor(),
                command.payload().mentionAll(),
                command.sendIntervalMs(),
                correlation.source(),
                marketing == null ? null : marketing.taskId(),
                marketing == null ? null : marketing.targetId(),
                marketing == null ? null : marketing.attemptId(),
                // 轮次号是普通营销和通讯录营销共用的 wire 字段，两种来源都要能填上
                marketing != null
                        ? marketing.roundNo()
                        : contactTask != null ? contactTask.roundNo()
                        : feedTask == null ? null : feedTask.roundNo(),
                groupCreation == null ? null : groupCreation.taskId(),
                groupCreation == null ? null : groupCreation.itemId(),
                historicalGroup == null ? null : historicalGroup.executionId(),
                historicalGroup == null ? null : historicalGroup.memberId(),
                contactTask == null ? null : contactTask.taskId(),
                contactTask == null ? null : contactTask.taskAccountId(),
                contactTask == null ? null : contactTask.recipientId(),
                feedTask == null ? null : feedTask.taskId(),
                feedTask == null ? null : feedTask.taskAccountId(),
                hyperlink == null ? null : hyperlink.taskId(),
                hyperlink == null ? null : hyperlink.recipientId());
        return new ProtocolMessageOutboxCommand(
                command,
                ProtocolBackend.ANDROID,
                properties.getMessageTopic(),
                command.account().protocolAccountId(),
                payload);
    }

    /** 把内存图片转换为不含物理 Redis Key 的 Kafka 资源引用。 */
    private static AndroidMediaPayload media(
            Long tenantId,
            MessageSendCommand.MessageMedia media,
            ResolvedMediaRegistry registry) {
        if (media == null) {
            return null;
        }
        return new AndroidMediaPayload(registry.get(tenantId, media));
    }

    /** 转换链接卡片；缩略图为空时保持为空，由 Android 原生发送器按无图卡片处理。 */
    private static AndroidLinkCardPayload linkCard(
            Long tenantId,
            MessageSendCommand.MessageLinkCard card,
            ResolvedMediaRegistry registry) {
        if (card == null) {
            return null;
        }
        return new AndroidLinkCardPayload(
                card.url(),
                card.title(),
                card.description(),
                media(tenantId, card.thumbnail(), registry));
    }

    /** 转换按钮卡片；按钮合法性已在写 outbox 前由 {@link #validateButtonCard} 保证。 */
    private static AndroidButtonCardPayload buttonCard(
            Long tenantId,
            MessageSendCommand.MessageButtonCard card,
            ResolvedMediaRegistry registry) {
        if (card == null) {
            return null;
        }
        List<AndroidButtonPayload> buttons = card.buttons() == null
                ? null
                : card.buttons().stream()
                        .map(button -> new AndroidButtonPayload(
                                button.type(), button.displayText(), button.value()))
                        .toList();
        return new AndroidButtonCardPayload(
                card.title(),
                card.footer(),
                buttons,
                media(tenantId, card.thumbnail(), registry));
    }

    /** Android Zhuan 的 {@code message.send.requested} Kafka 业务 payload。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AndroidMessagePayload(
            Long tenantId,
            Long accountId,
            String protocolAccountId,
            String wsPhone,
            String jid,
            String targetKind,
            String groupJid,
            String messageType,
            String text,
            AndroidMediaPayload image,
            AndroidLinkCardPayload linkCard,
            AndroidButtonCardPayload buttonCard,
            List<String> statusJidList,
            String backgroundColor,
            String textColor,
            boolean mentionAll,
            int sendIntervalMs,
            String source,
            Long marketingTaskId,
            Long targetId,
            Long attemptId,
            Long roundNo,
            Long groupCreationTaskId,
            Long groupCreationItemId,
            Long historicalExecutionId,
            Long historicalMemberId,
            Long contactTaskId,
            Long taskAccountId,
            Long recipientId,
            Long feedTaskId,
            Long feedTaskAccountId,
            Long hyperlinkTaskId,
            Long hyperlinkRecipientId
    ) {
    }

    /** Android Kafka payload 中的 Redis 图片资源引用。 */
    private record AndroidMediaPayload(AndroidImageAssetRef assetRef) {
    }

    /** Android 链接卡片 wire payload。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AndroidLinkCardPayload(
            String url,
            String title,
            String description,
            AndroidMediaPayload thumbnail
    ) {
    }

    /** Android 单 CTA 按钮卡片 wire payload。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record AndroidButtonCardPayload(
            String title,
            String footer,
            List<AndroidButtonPayload> buttons,
            AndroidMediaPayload thumbnail
    ) {
    }

    /** Android 按钮 wire payload；当前能力校验保证批次中实际只出现一个 link 按钮。 */
    private record AndroidButtonPayload(String type, String displayText, String value) {
    }

    /** 本批次按租户和媒体对象保存的 Redis 图片引用。 */
    private record ResolvedMediaRegistry(
            Map<Long, IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef>>
                    references) {

        private AndroidImageAssetRef get(
                Long tenantId,
                MessageSendCommand.MessageMedia media) {
            IdentityHashMap<MessageSendCommand.MessageMedia, AndroidImageAssetRef> tenantReferences =
                    references.get(tenantId);
            AndroidImageAssetRef reference =
                    tenantReferences == null ? null : tenantReferences.get(media);
            if (reference == null) {
                throw new IllegalStateException("Android image asset reference is missing");
            }
            return reference;
        }
    }
}
