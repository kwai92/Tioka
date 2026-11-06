package com.tioka.server.support;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试组合注解。
 * <p>Java 24 下元注解 {@code @SpringBootTest} 偶发注解合并失败时，可改为在测试类上直接写
 * {@code @SpringBootTest @AutoConfigureMockMvc @Tag("it")}，并调用 {@link IntegrationTestSupport#register}。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
public @interface IntegrationTest {
}
