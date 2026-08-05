package com.armada.group.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

/** 群详情同步协议端口组合的 Spring 装配契约测试。 */
class GroupMetadataSyncProtocolPortsComponentTest {

    @Test
    void springComponentScanDiscoversProtocolPortBundle() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);

        assertThat(scanner.findCandidateComponents("com.armada.group.service"))
                .extracting(BeanDefinition::getBeanClassName)
                .contains(GroupMetadataSyncProtocolPorts.class.getName());
    }
}
