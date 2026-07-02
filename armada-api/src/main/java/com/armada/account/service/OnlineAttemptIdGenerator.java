package com.armada.account.service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

@Service
public class OnlineAttemptIdGenerator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public String nextId() {
        String timestamp = FORMATTER.format(LocalDateTime.now(ZoneOffset.UTC));
        String random = Long.toUnsignedString(ThreadLocalRandom.current().nextLong(), 36);
        String suffix = random.length() > 12 ? random.substring(0, 12) : random;
        if (suffix.length() < 6) {
            suffix = (suffix + "000000").substring(0, 6);
        }
        return "oa_" + timestamp + "_" + suffix;
    }
}
