package com.armada.boot.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.armada.account.model.dto.DeviceImportDTO;
import com.armada.boot.web.DeviceImportRequestConverter;
import com.armada.testsupport.DeviceImportTestData;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

class DeviceImportRequestConverterTest {

    @Test
    void limitsUnknownLengthStreamBeforeParsing() {
        MockHttpInputMessage input = new MockHttpInputMessage(new byte[DeviceImportRequestConverter.MAX_BODY_BYTES + 1]);
        assertThat(input.getHeaders().getContentLength()).isEqualTo(-1);
        assertThatThrownBy(() -> new DeviceImportRequestConverter().read(DeviceImportDTO.class, input))
                .isInstanceOf(MaxUploadSizeExceededException.class);
    }

    @Test
    void preservesPayloadWithoutDtoStringDisclosure() throws Exception {
        String sentinel = DeviceImportTestData.payload("999000000001");
        MockHttpInputMessage input = new MockHttpInputMessage(DeviceImportTestData.body("999000000001", sentinel)
                .getBytes(StandardCharsets.UTF_8));
        DeviceImportDTO result = new DeviceImportRequestConverter().read(DeviceImportDTO.class, input);
        assertThat(result.payload().equals(sentinel)).isTrue();
        assertThat(result.toString().contains(sentinel)).isFalse();
    }
}
