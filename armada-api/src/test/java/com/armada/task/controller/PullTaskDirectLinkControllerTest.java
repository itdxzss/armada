package com.armada.task.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.armada.shared.security.AuthPrincipal;
import com.armada.task.model.dto.PullTaskDirectLinkCreateDTO;
import com.armada.task.model.dto.PullTaskDirectCreateRequest;
import com.armada.task.model.dto.PullTaskSimpleNewGroupCreateDTO;
import com.armada.task.model.vo.PullTaskStandardCreatedVO;
import com.armada.task.service.impl.PullTaskDirectLinkCreateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.multipart.MultipartFile;

/** HTTP 层证明 JSON part 与多文件共享一次正式创建请求。 */
class PullTaskDirectLinkControllerTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bindsJsonRequestAndRepeatedFilesParts(boolean simple) throws Exception {
        var service = mock(PullTaskDirectLinkCreateService.class);
        when(service.create(any(), any(), any())).thenReturn(new PullTaskStandardCreatedVO(1L, "新模式", "WAIT_START", 2, 2));
        var principal = new AuthPrincipal(2L, 7L, "operator", "操作员", "t", "租户", List.of(), List.of());
        var resolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AuthPrincipal.class;
            }

            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer container,
                    NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return principal;
            }
        };
        Object controller = simple ? new PullTaskSimpleNewGroupController(service) : new PullTaskDirectLinkController(service);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(resolver).build();
        PullTaskDirectCreateRequest request = simple
                ? new PullTaskSimpleNewGroupCreateDTO(UUID.randomUUID().toString(), "新模式", null, 0,
                        List.of(), 1, 0, 1, 3, 10, 2, 0, 1, 12L, null, null,
                        13L, 14L, null, false, "业务群", null, null, 15)
                : new PullTaskDirectLinkCreateDTO(UUID.randomUUID().toString(), "新模式", null, 0,
                        null, "link", List.of(), 1, 2, 3, 5, 15, 2, 0, 1, 12L, null, null);
        var requestPart = new MockMultipartFile("request", "", "application/json", new ObjectMapper().writeValueAsBytes(request));

        mvc.perform(multipart("/api/pull-tasks/standard/" + (simple ? "simple-new-group" : "direct-link")).file(requestPart)
                        .file(new MockMultipartFile("files", "a.txt", "text/plain", "919876543210A".getBytes()))
                        .file(new MockMultipartFile("files", "b.txt", "text/plain", "919876543211".getBytes())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MultipartFile>> files = ArgumentCaptor.forClass(List.class);
        verify(service).create(org.mockito.ArgumentMatchers.eq(request), files.capture(), org.mockito.ArgumentMatchers.eq(principal));
        assertThat(files.getValue()).extracting(MultipartFile::getOriginalFilename).containsExactly("a.txt", "b.txt");
    }
}
