package com.krakenplugins.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Checks a plugin jar against the Kraken API the client runs, before it reaches users:
 * <ul>
 *     <li>every Kraken API class, field and method the plugin uses exists in that API, since anything missing throws
 *     {@code NoClassDefFoundError} or {@code NoSuchMethodError} in the client;</li>
 *     <li>the jar holds a class annotated with {@code @PluginDescriptor}, without which the client loads nothing;</li>
 *     <li>no class targets a newer Java than the client runs.</li>
 * </ul>
 * It also warns about bundled copies of RuneLite or Kraken API classes, which the client ignores in favour of its own.
 */
public final class PluginJarCheck {

    /**
     * Java 17, the runtime RuneLite bundles.
     */
    static final int MAX_MAJOR_VERSION = 61;
    static final String PLUGIN_DESCRIPTOR = "net/runelite/client/plugins/PluginDescriptor";
    private static final String PLUGINS_PACKAGE = "net/runelite/client/plugins/";
    private static final String[] PROVIDED_PACKAGES = {ApiSurface.API_PACKAGE, "net/runelite/api/", "net/runelite/client/"};

    private final List<String> errors;
    private final List<String> warnings;
    private final int checkedReferences;

    private PluginJarCheck(List<String> errors, List<String> warnings, int checkedReferences) {
        this.errors = Collections.unmodifiableList(errors);
        this.warnings = Collections.unmodifiableList(warnings);
        this.checkedReferences = checkedReferences;
    }

    /**
     * Checks a plugin jar.
     *
     * @param jar        the plugin jar
     * @param api        the Kraken API the client runs
     * @param apiVersion that API's version, named in messages
     * @return the result
     * @throws IOException when the jar can't be read
     */
    public static PluginJarCheck check(File jar, ApiSurface api, String apiVersion) throws IOException {
        Map<String, ClassFile> classes = readClasses(jar);
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        boolean hasDescriptor = classes.values().stream().anyMatch(c -> c.getAnnotations().contains(PLUGIN_DESCRIPTOR));
        if (!hasDescriptor) {
            errors.add(jar.getName() + " has no class annotated with @PluginDescriptor, so the Kraken client won't find a plugin in it.");
        }

        List<String> bundled = new ArrayList<>();
        for (ClassFile classFile : classes.values()) {
            if (classFile.getMajorVersion() > MAX_MAJOR_VERSION) {
                errors.add(classFile.getName().replace('/', '.') + " is compiled for Java " + (classFile.getMajorVersion() - 44)
                        + ", but the client runs Java 17. Set options.release = 17 on the compile task.");
            }
            if (isProvided(classFile.getName())) {
                bundled.add(classFile.getName());
            }
        }
        if (!bundled.isEmpty()) {
            warnings.add(jar.getName() + " bundles " + bundled.size() + " RuneLite or Kraken API classes (such as "
                    + bundled.get(0).replace('/', '.') + "). The client always uses its own copies, so declare these"
                    + " dependencies compileOnly to keep them out of the jar.");
        }

        // Each missing class or member is reported once, naming the first plugin class that uses it.
        Map<String, String> missing = new LinkedHashMap<>();
        Map<String, Integer> uses = new HashMap<>();
        int checked = 0;
        for (ClassFile classFile : classes.values()) {
            if (isProvided(classFile.getName())) {
                continue;
            }
            String user = classFile.getName().replace('/', '.');
            for (String reference : classFile.getClassReferences()) {
                String type = elementType(reference);
                if (type != null && type.startsWith(ApiSurface.API_PACKAGE) && !classes.containsKey(type)) {
                    checked++;
                    if (!api.hasClass(type)) {
                        String key = "class " + type.replace('/', '.');
                        missing.putIfAbsent(key, user + " uses " + key);
                        uses.merge(key, 1, Integer::sum);
                    }
                }
            }
            for (ClassFile.MemberReference reference : classFile.getMemberReferences()) {
                if (!reference.getOwner().startsWith(ApiSurface.API_PACKAGE) || classes.containsKey(reference.getOwner())) {
                    continue;
                }
                checked++;
                if (api.resolve(reference) == ApiSurface.Resolution.MISSING) {
                    String key = ApiSurface.Descriptors.describe(reference);
                    missing.putIfAbsent(key, user + (reference.isField() ? " uses " : " calls ") + key);
                    uses.merge(key, 1, Integer::sum);
                }
            }
        }
        missing.forEach((key, message) -> {
            int otherUsers = uses.get(key) - 1;
            errors.add(message + (otherUsers > 0 ? " (and " + otherUsers + " more)" : "")
                    + ", which Kraken API " + apiVersion + " doesn't have.");
        });

        return new PluginJarCheck(errors, warnings, checked);
    }

    private static Map<String, ClassFile> readClasses(File jar) throws IOException {
        Map<String, ClassFile> classes = new LinkedHashMap<>();
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                // Multi-release variants and module descriptors aren't loaded as plugin classes.
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")
                        || entry.getName().endsWith("module-info.class")) {
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    ClassFile classFile = ClassFile.parse(in.readAllBytes());
                    classes.put(classFile.getName(), classFile);
                } catch (IOException e) {
                    throw new IOException("Could not read " + entry.getName() + " in " + jar.getName() + ": " + e.getMessage(), e);
                }
            }
        }
        return classes;
    }

    /**
     * Whether a class belongs to the Kraken API or RuneLite rather than the plugin. Plugins may live under
     * {@code net.runelite.client.plugins}, the package RuneLite's own plugins use, so that package counts as the plugin's.
     */
    private static boolean isProvided(String internalName) {
        if (internalName.startsWith(PLUGINS_PACKAGE)) {
            return false;
        }
        for (String providedPackage : PROVIDED_PACKAGES) {
            if (internalName.startsWith(providedPackage)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the class an entry names, unwrapping array descriptors such as {@code [Lcom/kraken/api/Foo;}, or
     * {@code null} for primitive arrays.
     */
    private static String elementType(String reference) {
        if (!reference.startsWith("[")) {
            return reference;
        }
        int start = reference.lastIndexOf('[') + 1;
        if (reference.charAt(start) != 'L') {
            return null;
        }
        return reference.substring(start + 1, reference.length() - 1);
    }

    public List<String> getErrors() {
        return errors;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /**
     * @return how many Kraken API class and member references were checked
     */
    public int getCheckedReferences() {
        return checkedReferences;
    }
}
