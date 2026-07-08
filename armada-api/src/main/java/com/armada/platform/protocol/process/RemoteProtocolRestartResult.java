package com.armada.platform.protocol.process;

import java.util.List;

/**
 * 协议层 admin 重启接口返回结果。
 *
 * @param success                协议层是否成功提交 PM2 重启
 * @param command                协议层实际执行或计划执行的重启命令文本
 * @param masterProcess          master 进程名
 * @param workerProcesses        worker 进程名列表
 * @param masterRestartScheduled master 是否已进入重启计划
 * @param message                协议层返回的结果消息
 */
public record RemoteProtocolRestartResult(
        boolean success,
        String command,
        String masterProcess,
        List<String> workerProcesses,
        boolean masterRestartScheduled,
        String message) {

    public RemoteProtocolRestartResult {
        command = command == null ? "" : command;
        masterProcess = masterProcess == null ? "" : masterProcess;
        workerProcesses = workerProcesses == null ? List.of() : List.copyOf(workerProcesses);
        message = message == null ? "" : message;
    }

    /**
     * 创建协议层重启成功结果。
     *
     * @param command                重启命令文本
     * @param masterProcess          master 进程名
     * @param workerProcesses        worker 进程名列表
     * @param masterRestartScheduled master 是否已进入重启计划
     * @param message                成功消息
     * @return 成功结果
     */
    public static RemoteProtocolRestartResult success(String command,
                                                      String masterProcess,
                                                      List<String> workerProcesses,
                                                      boolean masterRestartScheduled,
                                                      String message) {
        return new RemoteProtocolRestartResult(true, command, masterProcess, workerProcesses, masterRestartScheduled, message);
    }

    /**
     * 创建协议层重启失败结果。
     *
     * @param command                重启命令文本
     * @param masterProcess          master 进程名
     * @param workerProcesses        worker 进程名列表
     * @param masterRestartScheduled master 是否已进入重启计划
     * @param message                失败消息
     * @return 失败结果
     */
    public static RemoteProtocolRestartResult failure(String command,
                                                      String masterProcess,
                                                      List<String> workerProcesses,
                                                      boolean masterRestartScheduled,
                                                      String message) {
        return new RemoteProtocolRestartResult(false, command, masterProcess, workerProcesses, masterRestartScheduled, message);
    }
}
