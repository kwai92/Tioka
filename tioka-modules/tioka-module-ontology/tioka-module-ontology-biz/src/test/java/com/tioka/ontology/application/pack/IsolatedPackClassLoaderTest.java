package com.tioka.ontology.application.pack;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 隔离类加载器（M3-03 §10）：只暴露 ontology-api + SPI 契约 + JDK，
 * 其余 com.tioka.. 内部类一律拒绝；SPI 契约类与宿主同一 Class（跨类加载器 instanceof 成立）。
 */
class IsolatedPackClassLoaderTest {

    private static URL ontologyApiCodeSource() throws Exception {
        var pd = com.tioka.ontology.api.mq.OntologyMqTopics.class.getProtectionDomain();
        return pd.getCodeSource().getLocation();
    }

    private static IsolatedPackClassLoader loader() throws Exception {
        // 空 jar 路径仅作为 URL 占位；本测试不加载包内类
        return IsolatedPackClassLoader.forPack(Path.of("pom.xml"), ontologyApiCodeSource());
    }

    @Test
    void spi契约类直通且与宿主同一Class() throws Exception {
        try (IsolatedPackClassLoader loader = loader()) {
            Class<?> pack = loader.loadClass(DomainPack.class.getName());
            assertSame(DomainPack.class, pack, "SPI 契约类必须与宿主同一 Class");
            for (String name : IsolatedPackClassLoader.SPI_CLASSES) {
                assertSame(Class.forName(name), loader.loadClass(name), name);
            }
        }
    }

    @Test
    void ontologyApi类可加载() throws Exception {
        try (IsolatedPackClassLoader loader = loader()) {
            Class<?> topics = loader.loadClass("com.tioka.ontology.api.mq.OntologyMqTopics");
            assertSame(com.tioka.ontology.api.mq.OntologyMqTopics.class, topics);
        }
    }

    @Test
    void tioka内部类拒绝() throws Exception {
        try (IsolatedPackClassLoader loader = loader()) {
            // framework / biz 内部类不可见
            ClassNotFoundException e1 = assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.tioka.framework.json.CanonicalJson"));
            assertTrue(e1.getMessage().contains("不暴露"), e1.getMessage());
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.tioka.ontology.infrastructure.OntDraftRepository"));
            assertThrows(ClassNotFoundException.class,
                    () -> loader.loadClass("com.tioka.ontology.application.pack.DomainPackLoader"));
        }
    }

    @Test
    void jdk类可用且未知类报错() throws Exception {
        try (IsolatedPackClassLoader loader = loader()) {
            assertSame(java.util.HashMap.class, loader.loadClass("java.util.HashMap"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.demo.NotExist"));
        }
    }
}
