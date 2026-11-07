package com.tioka.infra.application.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.infra.api.plugin.PluginDescriptor;
import com.tioka.infra.api.plugin.PluginHost;
import com.tioka.infra.api.plugin.PluginLoadResult;
import com.tioka.infra.api.plugin.SpiType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginHostIT {

    private static final String KEY_HEX =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private PluginHost host;
    private ObjectMapper mapper;

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        host = new DefaultPluginHost(
                mapper,
                new PluginSignatureVerifier(List.of(KEY_HEX), true));
    }

    @Test
    void signatureRequired() throws Exception {
        Path jar = buildJar("demo.a", "1.0.0", SpiType.DOMAIN_PACK, List.of("cpu"),
                "com.demo.OkPlugin", "", okSource());
        PluginLoadResult result = host.enable(jar);
        assertEquals(PluginLoadResult.PluginStatus.FAILED, result.status());
        assertTrue(result.error().toLowerCase().contains("signature"));
    }

    @Test
    void permissionDenied() throws Exception {
        Path jar = signedJar("demo.pack", "1.0.0", SpiType.DOMAIN_PACK, List.of("network"),
                "com.demo.OkPlugin", okSource());
        PluginLoadResult result = host.enable(jar);
        assertEquals(PluginLoadResult.PluginStatus.FAILED, result.status());
        assertTrue(result.error().toLowerCase().contains("permission"));
    }

    @Test
    void isolation() throws Exception {
        String source = """
                package com.demo;
                public class ProbePlugin {
                  public ProbePlugin() throws Exception {
                    Class.forName("com.tioka.infra.application.TraceService", true, getClass().getClassLoader());
                  }
                }
                """;
        Path jar = signedJar("demo.iso", "1.0.0", SpiType.DOMAIN_PACK, List.of("cpu"),
                "com.demo.ProbePlugin", source);
        PluginLoadResult result = host.enable(jar);
        assertEquals(PluginLoadResult.PluginStatus.FAILED, result.status(), () -> String.valueOf(result));
        assertTrue(
                result.error() != null && result.error().contains("plugin isolation blocked"),
                () -> "error=" + result.error());
    }

    @Test
    void versionsCoexist() throws Exception {
        Path v1 = signedJar("demo.multi", "1.0.0", SpiType.DOMAIN_PACK, List.of("cpu"),
                "com.demo.OkPlugin", okSource());
        Path v2 = signedJar("demo.multi", "2.0.0", SpiType.DOMAIN_PACK, List.of("cpu"),
                "com.demo.OkPlugin", okSource());
        assertEquals(PluginLoadResult.PluginStatus.ENABLED, host.enable(v1).status());
        assertEquals(PluginLoadResult.PluginStatus.ENABLED, host.enable(v2).status());
        assertTrue(host.getEntry("demo.multi", "1.0.0").isPresent());
        assertTrue(host.getEntry("demo.multi", "2.0.0").isPresent());
    }

    @Test
    void failureIsolated() throws Exception {
        Path bad = buildJar("demo.bad", "1.0.0", SpiType.DOMAIN_PACK, List.of("cpu"),
                "com.demo.OkPlugin", "deadbeef", okSource());
        assertEquals(PluginLoadResult.PluginStatus.FAILED, host.enable(bad).status());

        Path good = signedJar("demo.good", "1.0.0", SpiType.DOMAIN_PACK, List.of("cpu"),
                "com.demo.OkPlugin", okSource());
        assertEquals(PluginLoadResult.PluginStatus.ENABLED, host.enable(good).status());
        assertTrue(host.getEntry("demo.good", "1.0.0").isPresent());
    }

    private static String okSource() {
        return """
                package com.demo;
                public class OkPlugin {
                  public OkPlugin() {}
                }
                """;
    }

    private Path signedJar(
            String key,
            String version,
            SpiType spiType,
            List<String> permissions,
            String entryClass,
            String source) throws Exception {
        Path unsigned = buildJar(key, version, spiType, permissions, entryClass, "", source);
        byte[] classBytes = readClassBytes(unsigned, entryClass);
        PluginDescriptor unsignedDesc = new PluginDescriptor(
                key, version, spiType, "1", permissions, entryClass, "");
        String sig = PluginSignatureVerifier.sign(unsignedDesc, classBytes, KEY_HEX);
        return buildJar(key, version, spiType, permissions, entryClass, sig, source);
    }

    private Path buildJar(
            String key,
            String version,
            SpiType spiType,
            List<String> permissions,
            String entryClass,
            String signature,
            String source) throws Exception {
        Path work = Files.createTempDirectory(temp, "plugin-build");
        Path srcDir = work.resolve("src/com/demo");
        Path classes = work.resolve("classes");
        Files.createDirectories(srcDir);
        Files.createDirectories(classes);
        String simple = entryClass.substring(entryClass.lastIndexOf('.') + 1);
        Files.writeString(srcDir.resolve(simple + ".java"), source, StandardCharsets.UTF_8);

        Process compile = new ProcessBuilder(
                "javac", "-d", classes.toString(), srcDir.resolve(simple + ".java").toString())
                .redirectErrorStream(true)
                .start();
        String compileOut = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (compile.waitFor() != 0) {
            throw new IllegalStateException("javac failed: " + compileOut);
        }

        PluginDescriptor descriptor = new PluginDescriptor(
                key, version, spiType, "1", permissions, entryClass, signature == null ? "" : signature);
        byte[] descriptorJson = mapper.writeValueAsBytes(descriptor);

        Path jar = work.resolve(key + "-" + version + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("tioka-plugin.json"));
            jos.write(descriptorJson);
            jos.closeEntry();
            Path classFile = classes.resolve("com/demo/" + simple + ".class");
            jos.putNextEntry(new JarEntry("com/demo/" + simple + ".class"));
            Files.copy(classFile, jos);
            jos.closeEntry();
        }
        return jar;
    }

    private static byte[] readClassBytes(Path jar, String entryClass) throws Exception {
        String path = entryClass.replace('.', '/') + ".class";
        try (var jf = new java.util.jar.JarFile(jar.toFile())) {
            var entry = jf.getJarEntry(path);
            try (var in = jf.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }
}
