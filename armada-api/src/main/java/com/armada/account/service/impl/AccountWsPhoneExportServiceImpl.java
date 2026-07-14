package com.armada.account.service.impl;

import com.armada.account.mapper.AccountMapper;
import com.armada.account.model.dto.AccountWsPhoneExportDTO;
import com.armada.account.model.entity.AccountStateCode;
import com.armada.account.model.vo.AccountWsPhoneExportFile;
import com.armada.account.model.vo.AccountWsPhoneExportRow;
import com.armada.account.service.AccountWsPhoneExportService;
import com.armada.shared.exception.BusinessException;
import com.armada.shared.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 所选正常账号 WS 号码导出实现。 */
@Service
public class AccountWsPhoneExportServiceImpl implements AccountWsPhoneExportService {

    private static final Logger log = LoggerFactory.getLogger(AccountWsPhoneExportServiceImpl.class);
    private static final int MAX_IDS = 2000;
    private static final int QUERY_CHUNK_SIZE = 500;
    private static final String DEFAULT_FILE_PREFIX = "全部WS号";
    private static final ZoneId EXPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String WINDOWS_FORBIDDEN = "<>:\"/\\|?*";

    private final AccountMapper accountMapper;
    private final Clock clock;

    @Autowired
    public AccountWsPhoneExportServiceImpl(AccountMapper accountMapper) {
        this(accountMapper, Clock.system(EXPORT_ZONE));
    }

    AccountWsPhoneExportServiceImpl(AccountMapper accountMapper, Clock clock) {
        this.accountMapper = accountMapper;
        this.clock = clock;
    }

    @Override
    public AccountWsPhoneExportFile export(AccountWsPhoneExportDTO request) {
        List<Long> ids = normalizeIds(request == null ? null : request.ids());
        if (ids.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "账号 ID 列表不能为空");
        }
        if (ids.size() > MAX_IDS) {
            throw new BusinessException(ErrorCode.VALIDATION, "单次最多导出 2000 个账号");
        }

        try {
            // 固定 500 条分片，限制单条 IN SQL 的参数数量。
            Set<String> phones = new LinkedHashSet<>();
            for (int from = 0; from < ids.size(); from += QUERY_CHUNK_SIZE) {
                int to = Math.min(from + QUERY_CHUNK_SIZE, ids.size());
                List<AccountWsPhoneExportRow> rows = accountMapper.selectNormalWsPhonesByIds(
                        ids.subList(from, to), AccountStateCode.NORMAL);
                if (rows == null) {
                    continue;
                }
                for (AccountWsPhoneExportRow row : rows) {
                    String phone = digitsOnly(row == null ? null : row.getWsPhone());
                    if (!phone.isEmpty()) {
                        phones.add(phone);
                    }
                }
            }
            if (phones.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION,
                        "当前所选账号中没有可导出的有效WS号码。");
            }

            String content = String.join("\n", phones);
            String filename = buildFilename(request == null ? null : request.groupName());
            return new AccountWsPhoneExportFile(
                    filename,
                    content.getBytes(StandardCharsets.UTF_8),
                    phones.size());
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("账号 WS 号码导出失败 selectedCount={}", ids.size(), ex);
            throw new BusinessException(ErrorCode.ACCOUNT_WS_PHONE_EXPORT_FAILED);
        }
    }

    private static List<Long> normalizeIds(List<Long> source) {
        if (source == null) {
            return List.of();
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for (Long id : source) {
            if (id != null) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }

    private static String digitsOnly(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder digits = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            }
        }
        return digits.toString();
    }

    private String buildFilename(String groupName) {
        String prefix = safeFilePrefix(groupName);
        String date = LocalDate.now(clock).format(DATE_FORMAT);
        return prefix + "_" + date + ".txt";
    }

    private static String safeFilePrefix(String groupName) {
        if (groupName == null || groupName.isBlank()) {
            return DEFAULT_FILE_PREFIX;
        }
        String trimmed = groupName.trim();
        StringBuilder safe = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isISOControl(ch) || WINDOWS_FORBIDDEN.indexOf(ch) >= 0) {
                safe.append('_');
            } else {
                safe.append(ch);
            }
        }
        String result = safe.toString().replaceFirst("[. ]+$", "");
        return result.isBlank() ? DEFAULT_FILE_PREFIX : result;
    }
}
