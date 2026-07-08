package com.armada.marketing.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class GroupCreationMarketingTaskMapperContractTest {

    @Test
    void mapperMethodsDoNotExposeMoreThanFiveParameters() {
        Method[] overloadedMethods = GroupCreationMarketingTaskMapper.class.getDeclaredMethods();

        assertThat(Arrays.stream(overloadedMethods)
                .filter(method -> method.getParameterCount() > 5)
                .map(method -> method.getName() + "(" + method.getParameterCount() + ")")
                .toList())
                .isEmpty();
    }
}
