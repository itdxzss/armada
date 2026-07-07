package com.armada.platform.protocol.process;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "armada.protocol-restart")
public class ProtocolRestartProperties {

    private String pm2Bin = "pm2";
    private List<String> processNames = new ArrayList<>(List.of(
            "protocol-master",
            "protocol-worker-1",
            "protocol-worker-2",
            "protocol-worker-3",
            "protocol-worker-4"));
    private List<String> readyUrls = new ArrayList<>(List.of(
            "http://127.0.0.1:8080/readyz",
            "http://127.0.0.1:8081/readyz",
            "http://127.0.0.1:8082/readyz",
            "http://127.0.0.1:8083/readyz",
            "http://127.0.0.1:8084/readyz"));
    private long commandTimeoutMs = 30_000L;
    private long readyTimeoutMs = 60_000L;
    private long readyPollIntervalMs = 1_000L;
    private long readyRequestTimeoutMs = 2_000L;

    public List<String> restartCommand() {
        List<String> command = new ArrayList<>();
        command.add(pm2Bin);
        command.add("restart");
        command.addAll(processNames);
        command.add("--update-env");
        return command;
    }

    public void validate() {
        if (pm2Bin == null || pm2Bin.isBlank()) {
            throw new IllegalStateException("armada.protocol-restart.pm2-bin 不能为空");
        }
        if (processNames == null || processNames.isEmpty()) {
            throw new IllegalStateException("armada.protocol-restart.process-names 不能为空");
        }
        if (readyUrls == null || readyUrls.size() != processNames.size()) {
            throw new IllegalStateException("armada.protocol-restart.ready-urls 数量必须等于 process-names");
        }
        if (commandTimeoutMs <= 0 || readyTimeoutMs < 0 || readyPollIntervalMs < 0 || readyRequestTimeoutMs <= 0) {
            throw new IllegalStateException("armada.protocol-restart timeout 配置非法");
        }
    }

    public String getPm2Bin() {
        return pm2Bin;
    }

    public void setPm2Bin(String pm2Bin) {
        this.pm2Bin = pm2Bin;
    }

    public List<String> getProcessNames() {
        return processNames;
    }

    public void setProcessNames(List<String> processNames) {
        this.processNames = processNames;
    }

    public List<String> getReadyUrls() {
        return readyUrls;
    }

    public void setReadyUrls(List<String> readyUrls) {
        this.readyUrls = readyUrls;
    }

    public long getCommandTimeoutMs() {
        return commandTimeoutMs;
    }

    public void setCommandTimeoutMs(long commandTimeoutMs) {
        this.commandTimeoutMs = commandTimeoutMs;
    }

    public long getReadyTimeoutMs() {
        return readyTimeoutMs;
    }

    public void setReadyTimeoutMs(long readyTimeoutMs) {
        this.readyTimeoutMs = readyTimeoutMs;
    }

    public long getReadyPollIntervalMs() {
        return readyPollIntervalMs;
    }

    public void setReadyPollIntervalMs(long readyPollIntervalMs) {
        this.readyPollIntervalMs = readyPollIntervalMs;
    }

    public long getReadyRequestTimeoutMs() {
        return readyRequestTimeoutMs;
    }

    public void setReadyRequestTimeoutMs(long readyRequestTimeoutMs) {
        this.readyRequestTimeoutMs = readyRequestTimeoutMs;
    }
}
