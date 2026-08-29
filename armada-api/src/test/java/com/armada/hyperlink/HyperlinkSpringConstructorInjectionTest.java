package com.armada.hyperlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.util.ClassUtils;

/** Hyperlink Spring Bean 的构造器注入装配契约。 */
class HyperlinkSpringConstructorInjectionTest {

    @Test
    void beansWithMultipleConstructorsDeclareOneInjectionConstructor() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(true);

        List<String> ambiguousBeans = scanner.findCandidateComponents("com.armada.hyperlink")
                .stream()
                .map(BeanDefinition::getBeanClassName)
                .map(className -> ClassUtils.resolveClassName(className,
                        HyperlinkSpringConstructorInjectionTest.class.getClassLoader()))
                .filter(beanClass -> beanClass.getDeclaredConstructors().length > 1)
                .filter(beanClass -> injectionConstructorCount(beanClass) != 1)
                .map(Class::getName)
                .sorted()
                .toList();

        assertThat(ambiguousBeans)
                .as("Spring Bean 有多个构造器时必须明确唯一的 @Autowired 构造器")
                .isEmpty();
    }

    private static long injectionConstructorCount(Class<?> beanClass) {
        return Arrays.stream(beanClass.getDeclaredConstructors())
                .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                .count();
    }
}
