package com.armada.group.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.armada.group.service.GroupInvitePageProbe;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

/** 邀请页抓取可达性区分测试，不出网。 */
class HttpGroupInvitePageFetcherProbeTest {

    private static final String LINK = "chat.whatsapp.com/IIYjcDTmDtr5FPaU7yVoWJ";

    private static final String PROFILE_HTML = """
            <html><head>
            <meta property="og:title" content="真实群名" />
            </head></html>
            """;

    private static final String DEFAULT_ONLY_HTML = """
            <html><head>
            <meta property="og:title" content="WhatsApp" />
            <meta property="og:image" content="https://static.whatsapp.net/rsrc.php/v4/yR/r/y8-PTBaP90a.png" />
            </head></html>
            """;

    @Test
    void reachableWithProfileWhenPageReturnsRealSubject() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(200, PROFILE_HTML);

        GroupInvitePageProbe probe = fetcher.probe(LINK);

        assertThat(probe.reachable()).isTrue();
        assertThat(probe.metadata().hasProfile()).isTrue();
        assertThat(probe.metadata().waSubject()).isEqualTo("真实群名");
    }

    @Test
    void reachableWithoutProfileWhenPageOnlyHasWhatsappDefaults() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(200, DEFAULT_ONLY_HTML);

        GroupInvitePageProbe probe = fetcher.probe(LINK);

        // 页面能访问，只是链接已失效：这是 LINK_EXPIRED，不是检测未完成。
        assertThat(probe.reachable()).isTrue();
        assertThat(probe.metadata().hasProfile()).isFalse();
    }

    @Test
    void unreachableOnNonSuccessStatus() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(503, "");

        assertThat(fetcher.probe(LINK).reachable()).isFalse();
    }

    @Test
    void unreachableOnIoFailure() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any())).thenThrow(new IOException("connect timed out"));

        assertThat(new HttpGroupInvitePageFetcher(httpClient).probe(LINK).reachable()).isFalse();
    }

    @Test
    void fetchDelegatesToProbeMetadata() throws Exception {
        HttpGroupInvitePageFetcher fetcher = fetcherReturning(200, PROFILE_HTML);

        assertThat(fetcher.fetch(LINK)).isEqualTo(fetcher.probe(LINK).metadata());
    }

    @SuppressWarnings("unchecked")
    private static HttpGroupInvitePageFetcher fetcherReturning(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.<String>send(any(HttpRequest.class), any())).thenReturn(response);
        return new HttpGroupInvitePageFetcher(httpClient);
    }
}
