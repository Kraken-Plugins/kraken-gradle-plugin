package com.krakenplugins.gradle.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginJarCheckTest {

    private static final String DESCRIPTOR = "package net.runelite.client.plugins;\n"
            + "import java.lang.annotation.*;\n"
            + "@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.TYPE)\n"
            + "public @interface PluginDescriptor { String name(); String[] tags() default {}; }\n";
    private static final String BASE_QUERY = "package com.kraken.api.query;\n"
            + "public abstract class AbstractQuery<T> { public T first() { return null; } public int count() { return 0; } }\n";
    private static final String STATUS = "package com.kraken.api;\npublic enum Status { RUNNING, STOPPED }\n";

    @TempDir
    File directory;

    private File oldApi;
    private File newApi;
    private File descriptor;

    @BeforeEach
    void compileApis() throws IOException {
        descriptor = TestCompiler.jar(new File(directory, "descriptor.jar"), 11, Map.of(
                "net/runelite/client/plugins/PluginDescriptor.java", DESCRIPTOR));
        oldApi = TestCompiler.jar(new File(directory, "kraken-api-5.2.1.jar"), 11, Map.of(
                "com/kraken/api/query/AbstractQuery.java", BASE_QUERY,
                "com/kraken/api/Status.java", STATUS,
                "com/kraken/api/Context.java", "package com.kraken.api;\n"
                        + "public class Context { public static int TICK; public Context() {}\n"
                        + "  public com.kraken.api.query.NpcQuery npcs() { return null; } }\n",
                "com/kraken/api/query/NpcQuery.java", "package com.kraken.api.query;\n"
                        + "public class NpcQuery extends AbstractQuery<String> implements Comparable<NpcQuery> {\n"
                        + "  public NpcQuery withName(String name) { return this; }\n"
                        + "  public int compareTo(NpcQuery other) { return 0; } }\n"));
        newApi = TestCompiler.jar(new File(directory, "kraken-api-5.2.2.jar"), 11, Map.of(
                "com/kraken/api/query/AbstractQuery.java", BASE_QUERY,
                "com/kraken/api/Status.java", STATUS,
                "com/kraken/api/Context.java", "package com.kraken.api;\n"
                        + "public class Context { public static int TICK; public static int REGION; public Context() {}\n"
                        + "  public com.kraken.api.query.NpcQuery npcs() { return null; }\n"
                        + "  public com.kraken.api.query.BankQuery bank() { return null; } }\n",
                "com/kraken/api/query/NpcQuery.java", "package com.kraken.api.query;\n"
                        + "public class NpcQuery extends AbstractQuery<String> implements Comparable<NpcQuery> {\n"
                        + "  public NpcQuery withName(String name) { return this; }\n"
                        + "  public NpcQuery withId(int... ids) { return this; }\n"
                        + "  public int compareTo(NpcQuery other) { return 0; } }\n",
                "com/kraken/api/query/BankQuery.java", "package com.kraken.api.query;\npublic class BankQuery {}\n"));
    }

    @Test
    void passesAPluginUsingOnlyWhatTheRuntimeApiHas() throws IOException {
        File plugin = plugin("ctx.npcs().withName(\"Goblin\").first(); ctx.npcs().count(); ctx.npcs().compareTo(null);"
                + " ctx.toString(); ctx.hashCode(); int t = com.kraken.api.Context.TICK;"
                + " com.kraken.api.Status.valueOf(\"RUNNING\").name(); com.kraken.api.Status.values();"
                + " new com.kraken.api.Context();");

        PluginJarCheck result = PluginJarCheck.check(plugin, ApiSurface.read(oldApi), "5.2.1");

        assertEquals(0, result.getErrors().size(), String.valueOf(result.getErrors()));
        assertTrue(result.getCheckedReferences() > 10, String.valueOf(result.getCheckedReferences()));
    }

    @Test
    void reportsMethodsFieldsAndClassesTheRuntimeApiLacks() throws IOException {
        File plugin = plugin("ctx.npcs().withId(1, 2).first(); ctx.bank(); int r = com.kraken.api.Context.REGION;"
                + " Object o = ctx; boolean b = o instanceof com.kraken.api.query.BankQuery;");

        PluginJarCheck result = PluginJarCheck.check(plugin, ApiSurface.read(oldApi), "5.2.1");

        String errors = String.join("\n", result.getErrors());
        assertTrue(errors.contains("com.example.MyPlugin calls com.kraken.api.query.NpcQuery.withId(int[]), which Kraken API 5.2.1 doesn't have."), errors);
        assertTrue(errors.contains("com.kraken.api.Context.bank()"), errors);
        assertTrue(errors.contains("uses com.kraken.api.Context.REGION"), errors);
        assertTrue(errors.contains("uses class com.kraken.api.query.BankQuery"), errors);
        assertEquals(4, result.getErrors().size(), errors);
    }

    @Test
    void reportsAMissingDescriptorAndANewerJava() throws IOException {
        File plugin = TestCompiler.withMajorVersion(TestCompiler.jar(new File(directory, "plugin.jar"), 17, Map.of(
                "com/example/Helper.java", "package com.example;\npublic class Helper {}\n")), 65);

        PluginJarCheck result = PluginJarCheck.check(plugin, ApiSurface.read(oldApi), "5.2.1");

        String errors = String.join("\n", result.getErrors());
        assertTrue(errors.contains("has no class annotated with @PluginDescriptor"), errors);
        assertTrue(errors.contains("com.example.Helper is compiled for Java 21, but the client runs Java 17"), errors);
    }

    @Test
    void warnsAboutBundledApiClassesWithoutCheckingThem() throws IOException {
        Map<String, String> sources = new HashMap<>(pluginSources("ctx.npcs();"));
        sources.put("com/kraken/api/Extra.java", "package com.kraken.api;\npublic class Extra { public void x() { new Context().bank(); } }\n");
        File plugin = TestCompiler.jar(new File(directory, "plugin.jar"), 17, sources, newApi, descriptor);

        PluginJarCheck result = PluginJarCheck.check(plugin, ApiSurface.read(oldApi), "5.2.1");

        assertEquals(0, result.getErrors().size(), String.valueOf(result.getErrors()));
        assertTrue(result.getWarnings().get(0).contains("bundles 1 RuneLite or Kraken API classes (such as com.kraken.api.Extra)"),
                result.getWarnings().toString());
    }

    @Test
    void describesReferencesInSourceForm() {
        assertEquals("com.kraken.api.Bank.deposit(int, java.lang.String[][], long)",
                ApiSurface.Descriptors.describe(new ClassFile.MemberReference(false, "com/kraken/api/Bank", "deposit", "(I[[Ljava/lang/String;J)V")));
        assertEquals("new com.kraken.api.Bank()",
                ApiSurface.Descriptors.describe(new ClassFile.MemberReference(false, "com/kraken/api/Bank", "<init>", "()V")));
    }

    private File plugin(String body) throws IOException {
        return TestCompiler.jar(new File(directory, "plugin.jar"), 17, pluginSources(body), newApi, descriptor);
    }

    private static Map<String, String> pluginSources(String body) {
        return Map.of("com/example/MyPlugin.java", "package com.example;\n"
                + "@net.runelite.client.plugins.PluginDescriptor(name = \"My Plugin\", tags = {\"a\"})\n"
                + "public class MyPlugin { void run(com.kraken.api.Context ctx) { " + body + " } }\n");
    }
}
