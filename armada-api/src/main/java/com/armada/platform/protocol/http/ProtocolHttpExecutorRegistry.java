package com.armada.platform.protocol.http;

import com.armada.platform.protocol.model.enums.ProtocolBackend;

import java.util.EnumMap;
import java.util.Map;

/**
 * 按协议后端保存 HTTP 执行器的注册表。
 */
public final class ProtocolHttpExecutorRegistry {

    private final Map<ProtocolBackend, ProtocolHttpExecutor> executors;

    public ProtocolHttpExecutorRegistry(Map<ProtocolBackend, ProtocolHttpExecutor> executors) {
        EnumMap<ProtocolBackend, ProtocolHttpExecutor> copy = new EnumMap<>(ProtocolBackend.class);
        if (executors != null) {
            executors.forEach((backend, executor) -> {
                if (backend != null && executor != null) {
                    copy.put(backend, executor);
                }
            });
        }
        this.executors = Map.copyOf(copy);
    }

    /**
     * 获取指定协议后端的 HTTP 执行器。
     *
     * @param backend 协议后端
     * @return 已注册的 HTTP 执行器
     * @throws IllegalStateException 对应后端没有注册执行器时抛出
     */
    public ProtocolHttpExecutor required(ProtocolBackend backend) {
        ProtocolHttpExecutor executor = executors.get(backend);
        if (executor == null) {
            throw new IllegalStateException("协议后端 HTTP executor 未注册 backend=" + backend);
        }
        return executor;
    }
}
