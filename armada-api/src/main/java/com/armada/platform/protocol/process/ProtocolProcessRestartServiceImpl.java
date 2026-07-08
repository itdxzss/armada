package com.armada.platform.protocol.process;

import com.armada.platform.protocol.exception.ProtocolException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 协议层进程重启服务实现。
 *
 * <p>优先通过协议层 admin HTTP 接口触发远端 PM2 重启,随后轮询配置中的 ready URL。
 * 本类只编排重启请求和探活结果,不直接执行本机 shell 命令。</p>
 */
@Service
public class ProtocolProcessRestartServiceImpl implements ProtocolProcessRestartService {

    /** 协议重启日志。 */
    private static final Logger log = LoggerFactory.getLogger(ProtocolProcessRestartServiceImpl.class);

    /** 返回给前端的错误消息最大长度,避免协议层长堆栈撑爆响应。 */
    private static final int MESSAGE_LIMIT = 500;

    /** 协议重启配置,包含进程名、ready URL 和超时时间。 */
    private final ProtocolRestartProperties properties;

    /** 协议层 admin 重启客户端。 */
    private final ProtocolProcessRestartClient restartClient;

    /** ready URL 探活组件。 */
    private final ProtocolReadyProbe readyProbe;

    /**
     * 注入协议进程重启依赖。
     *
     * @param properties    协议重启配置
     * @param restartClient 协议层 admin 重启客户端
     * @param readyProbe    ready 探活组件
     */
    public ProtocolProcessRestartServiceImpl(ProtocolRestartProperties properties,
                                             ProtocolProcessRestartClient restartClient,
                                             ProtocolReadyProbe readyProbe) {
        this.properties = properties;
        this.restartClient = restartClient;
        this.readyProbe = readyProbe;
    }

    /**
     * 触发协议层远端重启并等待所有配置进程 ready。
     *
     * <p>重启 HTTP 请求失败、协议层返回失败或任一 ready URL 超时未恢复都会返回 success=false,
     * 但不会向 Controller 抛出裸异常。</p>
     *
     * @return 协议重启结果和每个进程的探活快照
     */
    @Override
    public ProtocolRestartVO restart() {
        properties.validate();
        long startedAt = System.currentTimeMillis();
        List<String> command = properties.restartCommand();
        String commandText = String.join(" ", command);
        log.warn("协议进程重启开始 endpoint=/v1/admin/restart-processes fallbackCommand={}", commandText);

        RemoteProtocolRestartResult restartResult;
        try {
            restartResult = restartClient.restart();
        } catch (ProtocolException ex) {
            return finish(false, commandText, startedAt, List.of(),
                    "协议层重启请求失败 " + clip(ex.getMessage()));
        }
        String remoteCommandText = firstText(restartResult.command(), commandText);
        if (!restartResult.success()) {
            return finish(false, remoteCommandText, startedAt, List.of(),
                    "PM2 重启失败 " + clip(restartResult.message()));
        }

        List<ProtocolRestartProcessVO> processes = waitForReady();
        boolean allReady = processes.stream().allMatch(ProtocolRestartProcessVO::ready);
        if (!allReady) {
            return finish(false, remoteCommandText, startedAt, processes, "协议进程未全部 ready");
        }
        return finish(true, remoteCommandText, startedAt, processes, "协议进程已重启");
    }

    private List<ProtocolRestartProcessVO> waitForReady() {
        long deadline = System.currentTimeMillis() + properties.getReadyTimeoutMs();
        sleep(Math.min(properties.getReadyPollIntervalMs(), properties.getReadyTimeoutMs()));
        List<ProtocolRestartProcessVO> latest = probeAll();
        while (!allReady(latest) && System.currentTimeMillis() < deadline) {
            sleep(properties.getReadyPollIntervalMs());
            latest = probeAll();
        }
        return latest;
    }

    private List<ProtocolRestartProcessVO> probeAll() {
        List<ProtocolRestartProcessVO> results = new ArrayList<>();
        Duration timeout = Duration.ofMillis(properties.getReadyRequestTimeoutMs());
        for (int i = 0; i < properties.getProcessNames().size(); i++) {
            String processName = properties.getProcessNames().get(i);
            String readyUrl = properties.getReadyUrls().get(i);
            ReadyProbeResult probeResult = readyProbe.probe(readyUrl, timeout);
            results.add(new ProtocolRestartProcessVO(
                    processName,
                    readyUrl,
                    probeResult.ready(),
                    probeResult.statusCode(),
                    probeResult.error(),
                    System.currentTimeMillis()));
        }
        return results;
    }

    private static boolean allReady(List<ProtocolRestartProcessVO> results) {
        return !results.isEmpty() && results.stream().allMatch(ProtocolRestartProcessVO::ready);
    }

    private static void sleep(long intervalMs) {
        if (intervalMs <= 0) {
            return;
        }
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static ProtocolRestartVO finish(boolean success,
                                            String command,
                                            long startedAt,
                                            List<ProtocolRestartProcessVO> processes,
                                            String message) {
        long finishedAt = System.currentTimeMillis();
        return new ProtocolRestartVO(
                success,
                command,
                startedAt,
                finishedAt,
                finishedAt - startedAt,
                processes,
                message);
    }

    private static String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("[\\r\\n]+", " ").trim();
        return normalized.length() <= MESSAGE_LIMIT ? normalized : normalized.substring(0, MESSAGE_LIMIT);
    }
}
