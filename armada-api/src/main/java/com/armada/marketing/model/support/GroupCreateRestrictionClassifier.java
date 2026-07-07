package com.armada.marketing.model.support;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import java.util.Locale;
import java.util.Optional;

public final class GroupCreateRestrictionClassifier {

    private static final String REASON_ACCOUNT_REACHOUT_RESTRICTED = "account_reachout_restricted";
    private static final String REASON_RATE_OVERLIMIT = "rate-overlimit";

    private GroupCreateRestrictionClassifier() {
    }

    public static Optional<String> restrictedReason(RuntimeException ex) {
        if (!(ex instanceof ProtocolException protocolException)
                || protocolException.errorCode() != ProtocolErrorCode.ACCOUNT_REACHOUT_RESTRICTED) {
            return Optional.empty();
        }
        String message = protocolException.getMessage() == null
                ? ""
                : protocolException.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains(REASON_RATE_OVERLIMIT)) {
            return Optional.of(REASON_RATE_OVERLIMIT);
        }
        if (message.contains(REASON_ACCOUNT_REACHOUT_RESTRICTED)) {
            return Optional.of(REASON_ACCOUNT_REACHOUT_RESTRICTED);
        }
        return Optional.of(REASON_ACCOUNT_REACHOUT_RESTRICTED);
    }
}
