package com.armada.platform.protocol.backend.android;

import com.armada.platform.protocol.exception.ProtocolErrorCode;
import com.armada.platform.protocol.exception.ProtocolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AndroidResponseDecoderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AndroidResponseDecoder decoder = new AndroidResponseDecoder();

    @Test
    void decodesSuccessDataWithoutExposingEnvelopeJsonToCallers() throws Exception {
        AndroidResponseEnvelope envelope = mapper.readValue("""
                {"Code":0,"Data":"通过邀请码进群成功, 群聊ID: 120363001","Msg":""}
                """, AndroidResponseEnvelope.class);

        AndroidDecodedResponse response = decoder.decode(envelope);

        assertThat(response.code()).isZero();
        assertThat(response.data().asText()).contains("120363001");
        assertThat(response.success()).isTrue();
    }

    @Test
    void extractsRawIqCodeFromAndroidFailureMessage() throws Exception {
        AndroidResponseEnvelope envelope = mapper.readValue("""
                {"Code":1003,"Data":null,"Msg":"通过邀请码进群失败, not-authorized, Code: 403"}
                """, AndroidResponseEnvelope.class);

        AndroidDecodedResponse response = decoder.decode(envelope);

        assertThat(response.code()).isEqualTo(1003);
        assertThat(response.rawProtocolCode()).isEqualTo("403");
        assertThat(response.success()).isFalse();
    }

    @Test
    void acceptsGinValidationShapeWithoutApplicationCode() throws Exception {
        AndroidResponseEnvelope envelope = mapper.readValue(
                "{\"error\":\"Key: 'ScanCodeDto.Code' Error\"}",
                AndroidResponseEnvelope.class);

        AndroidDecodedResponse response = decoder.decode(envelope);

        assertThat(response.validationError()).contains("ScanCodeDto.Code");
        assertThat(response.success()).isFalse();
    }

    @Test
    void rejectsEnvelopeWithoutCodeOrValidationError() throws Exception {
        AndroidResponseEnvelope envelope = mapper.readValue(
                "{\"Data\":null,\"Msg\":\"unknown\"}",
                AndroidResponseEnvelope.class);

        assertThatThrownBy(() -> decoder.decode(envelope))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }

    @Test
    void rejectsNullEnvelope() {
        assertThatThrownBy(() -> decoder.decode(null))
                .isInstanceOfSatisfying(ProtocolException.class,
                        ex -> assertThat(ex.errorCode())
                                .isEqualTo(ProtocolErrorCode.ANDROID_RESPONSE_UNRECOGNIZED));
    }
}
