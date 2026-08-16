package com.armada.account.converter;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FullParamsToSixConverterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FullParamsToSixConverter converter = new FullParamsToSixConverter();

    @Test
    void convert_mapsAndroidSixFieldsFromFullParams() {
        ObjectNode source = validSource();
        source.put("registrationID", 77);
        source.put("signPreKeyPrivateKey", "ignored-sign-private-test");

        FullParamsToSixConverter.Result result = converter.convert(source);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.phone()).isEqualTo("5210000000001");
        assertThat(result.credential()).hasSize(6);
        assertThat(result.credential().path("phone").asText()).isEqualTo("5210000000001");
        assertThat(result.credential().path("static_pub_key").asText()).isEqualTo("static-public-test");
        assertThat(result.credential().path("static_pri_key").asText()).isEqualTo("static-private-test");
        assertThat(result.credential().path("id_pub_key").asText()).isEqualTo("identity-public-test");
        assertThat(result.credential().path("id_pri_key").asText()).isEqualTo("identity-private-test");
        assertThat(result.credential().path("phone_id").asText()).isEqualTo("phone-uuid-test");
        assertThat(result.credential().has("registrationID")).isFalse();
        assertThat(result.credential().has("signPreKeyPrivateKey")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "phone",
            "clientStaticPublicKey",
            "clientStaticPrivateKey",
            "identityPublicKey",
            "identityPrivateKey",
            "phoneUUID"
    })
    void convert_missingRequiredField_returnsSafeCredentialError(String field) {
        ObjectNode source = validSource();
        source.remove(field);

        FullParamsToSixConverter.Result result = converter.convert(source);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error())
                .contains("凭据不全")
                .contains(field)
                .doesNotContain("static-private-test")
                .doesNotContain("identity-private-test");
    }

    @Test
    void convert_phoneAndJidMismatch_returnsSafeFormatError() {
        ObjectNode source = validSource();
        source.put("jid", "5210000000099");

        FullParamsToSixConverter.Result result = converter.convert(source);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error())
                .contains("phone")
                .contains("jid")
                .doesNotContain("5210000000001")
                .doesNotContain("5210000000099");
    }

    private ObjectNode validSource() {
        ObjectNode source = mapper.createObjectNode();
        source.put("phone", " 5210000000001 ");
        source.put("jid", "5210000000001");
        source.put("in", "10000000001");
        source.put("cc", "52");
        source.put("clientStaticPublicKey", " static-public-test ");
        source.put("clientStaticPrivateKey", " static-private-test ");
        source.put("identityPublicKey", " identity-public-test ");
        source.put("identityPrivateKey", " identity-private-test ");
        source.put("phoneUUID", " phone-uuid-test ");
        return source;
    }
}
