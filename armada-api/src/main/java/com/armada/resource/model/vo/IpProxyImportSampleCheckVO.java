package com.armada.resource.model.vo;

import java.util.List;

/**
 * IP 代理 TXT 导入前抽样检测结果。
 *
 * @param passed     是否全部样本检测通过
 * @param sampleSize 本次实际检测样本数
 * @param samples    样本逐条检测结果
 * @param errors     失败原因列表,含原始行号
 */
public record IpProxyImportSampleCheckVO(
        boolean passed,
        int sampleSize,
        List<SampleRow> samples,
        List<String> errors) {

    /**
     * 单条样本检测结果。
     *
     * @param lineNo       TXT 原始行号
     * @param host         代理地址
     * @param port         代理端口
     * @param passed       当前样本是否通过
     * @param outboundIp   检测到的出口 IP
     * @param countryCode  检测到的国家码
     * @param location     检测到的位置
     * @param errorMessage 失败原因
     */
    public record SampleRow(
            int lineNo,
            String host,
            Integer port,
            boolean passed,
            String outboundIp,
            String countryCode,
            String location,
            String errorMessage) {
    }
}
