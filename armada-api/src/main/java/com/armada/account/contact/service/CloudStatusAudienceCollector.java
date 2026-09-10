package com.armada.account.contact.service;

import com.armada.platform.protocol.port.ContactPort;
import com.armada.platform.protocol.model.command.CloudContactsQuery;
import com.armada.platform.protocol.model.command.ProtocolAccountRef;
import com.armada.platform.protocol.model.result.CloudContactsPage;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** 固定上限内读完同一版本的所有页；不返回部分结果。 */
@Component
public class CloudStatusAudienceCollector {
    private final ContactPort port;
    public CloudStatusAudienceCollector(ContactPort port) { this.port = port; }

    public Snapshot collect(ProtocolAccountRef account) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        Set<String> jids = new LinkedHashSet<>();
        Set<String> cursors = new HashSet<>();
        String cursor = "";
        String version = null;
        for (int pageNo = 0; pageNo < 51; pageNo++) {
            checkDeadline(deadline);
            CloudContactsPage page = port.cloudPage(new CloudContactsQuery(account, cursor));
            checkDeadline(deadline);
            validate(page);
            if (version != null && !version.equals(page.version())) { throw failure("CLOUD_VERSION_CHANGED"); }
            version = page.version();
            jids.addAll(page.jids());
            if (jids.size() > 5000) { throw failure("CLOUD_AUDIENCE_TOO_LARGE"); }
            if (!page.hasNextPage()) { return new Snapshot(List.copyOf(jids), version); }
            if (!cursors.add(page.nextCursor())) { throw failure("CLOUD_CURSOR_REPEATED"); }
            cursor = page.nextCursor();
        }
        throw failure("CLOUD_PAGE_LIMIT");
    }

    private static void validate(CloudContactsPage page) {
        if (page == null || page.version() == null || page.version().isBlank() || page.version().length() > 256
                || page.nextCursor() == null || page.nextCursor().length() > 4096 || page.jids().size() > 100
                // 协议排除账号自身后，中间页可以为空；仍由游标去重和分页上限防止空页循环。
                || (page.hasNextPage() && page.nextCursor().isBlank())
                || page.jids().stream().anyMatch(jid -> !jid.matches("[1-9][0-9]{0,19}@lid"))) {
            throw failure("CLOUD_INVALID_PAGE");
        }
    }

    private static void checkDeadline(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) { throw failure("CLOUD_FETCH_TIMEOUT"); }
    }
    private static IllegalStateException failure(String code) { return new IllegalStateException(code); }
    public record Snapshot(List<String> jids, String version) {}
}
