package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.model.result.CompanionPairCodeResult;
import com.armada.platform.protocol.port.CompanionPairCodePort;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Android Zhuan 主设备输入配对码的后端；原因码只认安卓层白名单，其余归为 UNKNOWN。 */
public final class AndroidNativeCompanionPairCodeAdapter implements CompanionPairCodePort {

    /** 安卓层 PairCodeInputService 公开的失败原因。 */
    private static final Set<String> KNOWN_REASONS =
            Set.of("ACCOUNT_OFFLINE", "EXPIRED", "NO_PENDING", "AMBIGUOUS", "SEND_FAILED");
    private static final String UNKNOWN = "UNKNOWN";

    private final AndroidNativeClient client;
    private final AndroidResponseDecoder decoder;

    public AndroidNativeCompanionPairCodeAdapter(AndroidNativeClient client, AndroidResponseDecoder decoder) {
        this.client = client;
        this.decoder = decoder;
    }

    @Override
    public CompanionPairCodeResult submit(String wsPhone, String pairingCode) {
        AndroidDecodedResponse response = decoder.decode(client.pairCodeInput(wsPhone, pairingCode));
        if (response.success()) {
            return new CompanionPairCodeResult(true, null);
        }
        return new CompanionPairCodeResult(false, reason(response.data()));
    }

    private static String reason(JsonNode data) {
        String reason = data == null ? null : data.path("reason").asText(null);
        return reason != null && KNOWN_REASONS.contains(reason) ? reason : UNKNOWN;
    }
}
