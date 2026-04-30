package com.authx.sdk;

import com.authx.sdk.model.CaveatContext;
import com.authx.sdk.model.CaveatRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthxCodegenCaveatBuilderTest {

    @TempDir
    private Path tempDir;

    @Test
    void emittedCaveatBuilderCompilesAndValidatesKnownParameters() throws Exception {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.put("client_ip", "string");
        parameters.put("expires_at", "timestamp");
        parameters.put("ttl", "duration");
        parameters.put("network", "ipaddress");
        parameters.put("payload", "bytes");

        String source = AuthxCodegen.emitCaveatClass(
                "ip_allowlist",
                parameters,
                "client_ip != \"\"",
                "",
                "com.example.schema");

        assertThat(source).contains(
                "public static Builder builder()",
                "public static CaveatRef completeRef(Object... keyValues)",
                "public static CaveatContext completeContext(Object... keyValues)",
                "public Builder clientIp(String value)",
                "public Builder expiresAt(Instant value)",
                "public Builder ttl(Duration value)",
                "public Builder network(InetAddress value)",
                "public Builder payload(byte[] value)",
                "Unknown caveat parameter");

        Class<?> caveatClass = compileAndLoad(source, "IpAllowlist");
        Instant expiresAt = Instant.parse("2026-05-01T00:00:00Z");
        Duration ttl = Duration.ofMinutes(5);
        InetAddress network = InetAddress.getByName("127.0.0.1");

        Object builder = caveatClass.getMethod("builder").invoke(null);
        caveatClass.getDeclaredClasses()[0].getMethod("clientIp", String.class)
                .invoke(builder, "10.0.0.5");
        caveatClass.getDeclaredClasses()[0].getMethod("expiresAt", Instant.class)
                .invoke(builder, expiresAt);
        caveatClass.getDeclaredClasses()[0].getMethod("ttl", Duration.class)
                .invoke(builder, ttl);
        caveatClass.getDeclaredClasses()[0].getMethod("network", InetAddress.class)
                .invoke(builder, network);
        caveatClass.getDeclaredClasses()[0].getMethod("payload", byte[].class)
                .invoke(builder, new Object[]{new byte[]{1, 2, 3}});

        CaveatContext context = (CaveatContext) caveatClass.getDeclaredClasses()[0]
                .getMethod("context")
                .invoke(builder);
        assertThat(context.values())
                .containsEntry("client_ip", "10.0.0.5")
                .containsEntry("expires_at", expiresAt)
                .containsEntry("ttl", ttl)
                .containsEntry("network", network);

        CaveatRef ref = (CaveatRef) caveatClass.getDeclaredClasses()[0]
                .getMethod("ref")
                .invoke(builder);
        assertThat(ref.name()).isEqualTo("ip_allowlist");
        assertThat(ref.context()).containsEntry("client_ip", "10.0.0.5");
    }

    @Test
    void emittedCaveatObjectPathFailsFastForUnknownMissingAndWrongTypes() throws Exception {
        LinkedHashMap<String, String> parameters = new LinkedHashMap<String, String>();
        parameters.put("client_ip", "string");
        parameters.put("expires_at", "timestamp");

        Class<?> caveatClass = compileAndLoad(AuthxCodegen.emitCaveatClass(
                "ip_allowlist",
                parameters,
                "",
                "",
                "com.example.schema"), "IpAllowlist");

        CaveatContext partial = (CaveatContext) caveatClass.getMethod("context", Object[].class)
                .invoke(null, (Object) new Object[]{"client_ip", "10.0.0.5"});
        assertThat(partial.values()).containsEntry("client_ip", "10.0.0.5");

        assertThatThrownBy(() -> caveatClass.getMethod("completeContext", Object[].class)
                .invoke(null, (Object) new Object[]{"client_ip", "10.0.0.5"}))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("Missing caveat parameter: expires_at");

        assertThatThrownBy(() -> caveatClass.getMethod("context", Object[].class)
                .invoke(null, (Object) new Object[]{"unknown", "value", "expires_at", Instant.now()}))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("Unknown caveat parameter: unknown");

        assertThatThrownBy(() -> caveatClass.getMethod("context", Object[].class)
                .invoke(null, (Object) new Object[]{"client_ip", 123L, "expires_at", Instant.now()}))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessageContaining("Parameter client_ip must be String");
    }

    private Class<?> compileAndLoad(String source, String className) throws Exception {
        Path packageDir = tempDir.resolve("com/example/schema");
        Files.createDirectories(packageDir);
        Path sourceFile = packageDir.resolve(className + ".java");
        Files.writeString(sourceFile, source);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int result = compiler.run(
                null,
                null,
                null,
                "-classpath",
                System.getProperty("java.class.path"),
                sourceFile.toString());
        assertThat(result).isZero();

        URLClassLoader loader = new URLClassLoader(
                new URL[]{tempDir.toUri().toURL()},
                getClass().getClassLoader());
        return Class.forName("com.example.schema." + className, true, loader);
    }
}
