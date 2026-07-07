package com.armada.platform.protocol.process;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProtocolProcessRestartServiceImpl implements ProtocolProcessRestartService {

    private static final Logger log = LoggerFactory.getLogger(ProtocolProcessRestartServiceImpl.class);
    private static final int MESSAGE_LIMIT = 500;

    private final ProtocolRestartProperties properties;
    private final ProtocolProcessCommandRunner commandRunner;
    private final ProtocolReadyProbe readyProbe;

    public ProtocolProcessRestartServiceImpl(ProtocolRestartProperties properties,
                                             ProtocolProcessCommandRunner commandRunner,
                                             ProtocolReadyProbe readyProbe) {
        this.properties = properties;
        this.commandRunner = commandRunner;
        this.readyProbe = readyProbe;
    }

    @Override
    public ProtocolRestartVO restart() {
        properties.validate();
        long startedAt = System.currentTimeMillis();
        List<String> command = properties.restartCommand();
        String commandText = String.join(" ", command);
        log.warn("协议进程重启开始 command={}", commandText);

        ProcessCommandResult commandResult = commandRunner.run(
                command,
                Duration.ofMillis(properties.getCommandTimeoutMs()));
        if (commandResult.timedOut()) {
            return finish(false, commandText, startedAt, List.of(), "PM2 重启超时");
        }
        if (commandResult.exitCode() != 0) {
            String detail = firstText(commandResult.stderr(), commandResult.stdout());
            return finish(false, commandText, startedAt, List.of(),
                    "PM2 重启失败 exitCode=" + commandResult.exitCode() + " " + clip(detail));
        }

        List<ProtocolRestartProcessVO> processes = waitForReady();
        boolean allReady = processes.stream().allMatch(ProtocolRestartProcessVO::ready);
        if (!allReady) {
            return finish(false, commandText, startedAt, processes, "协议进程未全部 ready");
        }
        return finish(true, commandText, startedAt, processes, "协议进程已重启");
    }

    private List<ProtocolRestartProcessVO> waitForReady() {
        long deadline = System.currentTimeMillis() + properties.getReadyTimeoutMs();
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
