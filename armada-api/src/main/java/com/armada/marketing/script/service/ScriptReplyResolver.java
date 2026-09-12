package com.armada.marketing.script.service;

import com.armada.marketing.mapper.ScriptMarketingSendRecordMapper;
import com.armada.marketing.model.dto.ScriptMarketingStepDTO;
import com.armada.marketing.model.entity.ScriptMarketingGroup;
import com.armada.marketing.model.entity.ScriptMarketingSendRecord;
import com.armada.platform.protocol.model.command.MessageQuoteContext;
import com.armada.platform.protocol.model.command.MessageReply;
import com.armada.platform.protocol.model.command.MessageSendCommand;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.FAILED;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.HELD;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SENDING;
import static com.armada.marketing.model.enums.ScriptMarketingStatus.SUCCESS;

/** 在任务锁内按当前群执行解析引用；原句不可引用时仅去掉引用，仍发送本句。 */
@Service
public class ScriptReplyResolver {
    private final ScriptMarketingSendRecordMapper records;
    private final ObjectMapper json;
    public ScriptReplyResolver(ScriptMarketingSendRecordMapper records, ObjectMapper json) {
        this.records = records; this.json = json;
    }
    /** 等待尚未收敛的原句；已确定的引用或普通发送决定随原命令一起提交。 */
    public Resolution resolve(ScriptMarketingGroup group, List<ScriptMarketingStepDTO> steps) {
        String target = steps.get(group.getNextStep()).replyToStepId();
        if (target == null || target.isEmpty()) return new Resolution(false, null, null);
        int index = -1;
        for (int i = 0; i < group.getNextStep(); i++) if (target.equals(steps.get(i).stepId())) index = i;
        if (index < 0) throw new BusinessException(ErrorCode.VALIDATION, "回复目标必须属于当前剧本前面的消息");
        var original = records.findStep(group.getId(), index);
        if (original == null) return new Resolution(false, null, "REPLY_TARGET_MISSING");
        if (!Objects.equals(group.getTaskId(), original.getTaskId())
                || !Objects.equals(group.getTenantId(), original.getTenantId())) {
            throw new BusinessException(ErrorCode.VALIDATION, "引用消息归属不符");
        }
        if (original.getStatus() == SENDING || original.getStatus() == HELD) return new Resolution(true, null, null);
        if (original.getStatus() != SUCCESS) return new Resolution(false, null,
                original.getStatus() == FAILED ? "REPLY_TARGET_FAILED" : "REPLY_TARGET_UNKNOWN");
        if (original.getMessageId() == null || original.getMessageId().isBlank() || original.getQuoteContextJson() == null)
            return new Resolution(false, null, "REPLY_CONTEXT_MISSING");
        try {
            var context = json.readValue(original.getQuoteContextJson(), MessageQuoteContext.class);
            if (context == null || !context.valid()) return new Resolution(false, null, "REPLY_CONTEXT_MISSING");
            return new Resolution(false, new MessageReply(original.getMessageId(), group.getGroupJid(), context), null);
        } catch (JsonProcessingException exception) {
            return new Resolution(false, null, "REPLY_CONTEXT_MISSING");
        }
    }
    /** 结果快照只补齐一次；格式异常不伪造上下文，后续本句可按普通消息发送。 */
    public void capture(ScriptMarketingSendRecord row, MessageQuoteContext context) {
        if (row.getQuoteContextJson() != null || context == null || !context.valid()) return;
        try { row.setQuoteContextJson(json.writeValueAsString(context)); }
        catch (JsonProcessingException exception) { throw new BusinessException(ErrorCode.CONFLICT, "引用快照无法持久化"); }
    }
    /** 可提交的发送决定；waiting 时不得创建意图、推进游标或入队。 */
    public record Resolution(boolean waiting, MessageReply reply, String fallbackReason) {
        public MessageSendCommand.MessagePayload apply(MessageSendCommand.MessagePayload payload) {
            return new MessageSendCommand.MessagePayload(payload.type(), payload.content(), payload.mentionAll(), reply);
        }
    }
}
