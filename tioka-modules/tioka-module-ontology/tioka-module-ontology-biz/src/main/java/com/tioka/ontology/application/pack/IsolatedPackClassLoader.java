package com.tioka.ontology.application.pack;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域包隔离类加载器（M3-03 §10：类加载器只向领域包暴露 {@code ontology-api} 与 JDK，
 * 不暴露其他 Tioka 内部类）。
 *
 * <p>实现：parent = null（仅 bootstrap 可见，JDK 可用），URL = [ontology-api codeSource, 领域包 jar]。
 * SPI 契约类（{@code DomainPack} 等，本阶段在 ont-biz pack 包，后续迁 ontology-api）经
 * 构造时捕获的 Class 直通暴露；其余 {@code com.tioka..} 类一律拒绝加载。
 */
public class IsolatedPackClassLoader extends URLClassLoader {

    /** SPI 直通白名单（全限定名）。 */
    static final List<String> SPI_CLASSES = List.of(
            DomainPack.class.getName(),
            PromptBundle.class.getName(),
            ModelValidator.class.getName(),
            FoldingEngine.class.getName(),
            ProjectionHook.class.getName(),
            CelFunction.class.getName(),
            Violation.class.getName());

    private static final String ONT_API_PACKAGE = "com.tioka.ontology.api.";

    private final Map<String, Class<?>> spiByWhitelist = new ConcurrentHashMap<>();

    public IsolatedPackClassLoader(URL[] urls) {
        super(urls, null);
        for (String name : SPI_CLASSES) {
            try {
                Class<?> c = Class.forName(name, false, IsolatedPackClassLoader.class.getClassLoader());
                spiByWhitelist.put(name, c);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("SPI 类不可达: " + name, e);
            }
        }
    }

    public static IsolatedPackClassLoader forPack(Path packJar, URL ontologyApiCodeSource) throws IOException {
        URL[] urls = ontologyApiCodeSource == null
                ? new URL[]{packJar.toUri().toURL()}
                : new URL[]{ontologyApiCodeSource, packJar.toUri().toURL()};
        return new IsolatedPackClassLoader(urls);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                Class<?> spi = spiByWhitelist.get(name);
                if (spi != null) {
                    c = spi;
                } else if (name.startsWith(ONT_API_PACKAGE)) {
                    // ontology-api 经宿主类加载器委派——保证与宿主同一 Class（跨类加载器
                    // instanceof / 类型一致）；由此也无需在 URL 里挂 api codeSource
                    c = IsolatedPackClassLoader.class.getClassLoader().loadClass(name);
                } else if (isTiokaInternal(name)) {
                    throw new ClassNotFoundException(
                            "domain-pack 类加载器不暴露 Tioka 内部类: " + name);
                } else {
                    // 非 Tioka 类：标准委派（parent=null → bootstrap 提供 JDK；再查包 URL）
                    c = super.loadClass(name, resolve);
                }
            }
            if (resolve && c != null && c.getClassLoader() == this) {
                resolveClass(c);
            }
            return c;
        }
    }

    /** Tioka 内部类判定：com.tioka.. 但不在暴露面（ontology-api、SPI 白名单）之内。 */
    private boolean isTiokaInternal(String name) {
        if (!name.startsWith("com.tioka.")) {
            return false;
        }
        return !(name.startsWith(ONT_API_PACKAGE) || SPI_CLASSES.contains(name));
    }
}
