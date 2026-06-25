package com.armada.account.service.impl;

import com.armada.account.mapper.AccountCredentialMapper;
import com.armada.account.mapper.AccountMapper;
import com.armada.account.mapper.AccountStateMapper;
import com.armada.account.model.entity.Account;
import com.armada.account.model.entity.AccountCredential;
import com.armada.account.model.entity.AccountState;
import com.armada.account.model.entity.ParsedEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单行三步原子写组件。
 *
 * <p>将 account + account_state + account_credential 三张表的写入封装在独立的 Spring Bean 中,
 * 使 {@code @Transactional} 能通过代理生效(规避 Spring 同类自调用 self-invocation 失效问题)。</p>
 *
 * <p>若 DB 唯一键冲突(uq_tenant_phone),本方法抛 {@code DuplicateKeyException},
 * 事务自动回滚,调用方捕获后标记为 DUPLICATE,不留孤儿行。</p>
 */
@Component
public class AccountImportRowWriter {

    private static final Logger log = LoggerFactory.getLogger(AccountImportRowWriter.class);

    private final AccountMapper accountMapper;
    private final AccountStateMapper stateMapper;
    private final AccountCredentialMapper credentialMapper;
    private final ObjectMapper objectMapper;

    public AccountImportRowWriter(AccountMapper accountMapper,
                                  AccountStateMapper stateMapper,
                                  AccountCredentialMapper credentialMapper) {
        this.accountMapper = accountMapper;
        this.stateMapper = stateMapper;
        this.credentialMapper = credentialMapper;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 三步原子写:插入 account → account_state(默认行) → account_credential。
     *
     * <p>全部在同一事务内执行。任何一步失败(包括 uq_tenant_phone 冲突)整体回滚,
     * 不留孤儿行。成功后返回新账号 ID。</p>
     *
     * @param wid          WA 手机号(纯数字,用于 wsPhone 和 protocolAccountId)
     * @param entry        解析条目(data 字段序列化为 creds_json;日志只打 maskPhone,不打明文)
     * @param accountGroupId 目标分组 ID(已由调用方解析;NOT NULL)
     * @param importFormat 导入格式编码(写入 account_credential.cred_format)
     * @param deviceOs     机型编码(选填,null 表示用户未选机型)
     * @param accountType  账号类型:导入即冻结,不可后续改写
     * @return 新写入的 account.id
     * @throws org.springframework.dao.DuplicateKeyException 若 uq_tenant_phone 冲突(跨批重复)
     */
    @Transactional(rollbackFor = Exception.class)
    public Long writeOne(String wid,
                         ParsedEntry entry,
                         Long accountGroupId,
                         int importFormat,
                         Integer deviceOs,
                         int accountType) {
        long now = System.currentTimeMillis();

        // 步骤 ①:插入 account 主行
        Account account = buildAccount(wid, accountGroupId, deviceOs, accountType, now);
        accountMapper.insert(account);
        Long accountId = account.getId();

        // 步骤 ②:插入 account_state 默认行(状态列全 NULL=未上报)
        AccountState state = buildAccountState(accountId, now);
        stateMapper.insert(state);

        // 步骤 ③:插入 account_credential(creds_json 由 data 序列化;日志只打 maskPhone+长度)
        String credsJson = serializeCredsJson(entry, wid);
        AccountCredential credential = buildCredential(accountId, wid, importFormat, credsJson, now);
        credentialMapper.insert(credential);

        log.info("[AccountImportRowWriter] 三步写成功 maskPhone={}*** accountId={} credsLen={}",
                wid.length() > 4 ? wid.substring(0, wid.length() - 4) : "****",
                accountId,
                credsJson == null ? 0 : credsJson.length());

        return accountId;
    }

    // ---- 私有构建方法 ----

    private Account buildAccount(String wid, Long accountGroupId, Integer deviceOs, int accountType, long now) {
        Account a = new Account();
        a.setWsPhone(wid);
        // 铁律:account_type 导入即冻结
        a.setAccountType(accountType);
        a.setDeviceOs(deviceOs);
        a.setOwnership(1);           // 默认「自有」
        a.setPriority(0);
        a.setAccountGroupId(accountGroupId);
        a.setProtocolAccountId("acc_" + wid);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return a;
    }

    private AccountState buildAccountState(Long accountId, long now) {
        AccountState s = new AccountState();
        s.setAccountId(accountId);
        // 状态列全不写(NULL=未上报),仅填计数初始值
        s.setProxyFailureCount(0);
        s.setPullIntoGroupCount(0);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return s;
    }

    private AccountCredential buildCredential(Long accountId, String wid,
                                               int importFormat, String credsJson, long now) {
        AccountCredential c = new AccountCredential();
        c.setAccountId(accountId);
        c.setWsPhone(wid);
        c.setCredFormat(importFormat);
        c.setCredsJson(credsJson);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    /**
     * 将 {@link ParsedEntry#getData()} 序列化为 JSON 字符串存入凭据列。
     * 序列化失败时返回原始 raw 标识(兜底;实际 data 来自 parse,不应失败)。
     * 铁律:日志只打 maskPhone+长度,不打 creds_json 明文。
     */
    private String serializeCredsJson(ParsedEntry entry, String wid) {
        if (entry.getData() == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(entry.getData());
        } catch (JsonProcessingException e) {
            log.warn("[AccountImportRowWriter] creds_json 序列化失败 maskPhone={}*** error={}",
                    wid.length() > 4 ? wid.substring(0, wid.length() - 4) : "****",
                    e.getMessage());
            return entry.getRaw();
        }
    }
}
