package com.krakenplugins.gradle.internal;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles Java sources into a jar, so checks can run against real class files.
 */
public final class TestCompiler {

    private TestCompiler() {
    }

    /**
     * Compiles sources into a jar.
     *
     * @param jar       the jar to write
     * @param release   the Java release to compile for
     * @param sources   source text keyed by path, such as {@code com/kraken/api/Context.java}
     * @param classpath jars to compile against
     * @return the jar
     * @throws IOException when compilation fails or the jar can't be written
     */
    public static File jar(File jar, int release, Map<String, String> sources, File... classpath) throws IOException {
        Path sourceDirectory = Files.createTempDirectory("sources");
        Path classDirectory = Files.createTempDirectory("classes");
        List<String> arguments = new ArrayList<>(List.of("--release", String.valueOf(release), "-d", classDirectory.toString()));
        if (classpath.length > 0) {
            arguments.add("-classpath");
            arguments.add(Stream.of(classpath).map(File::getAbsolutePath).collect(Collectors.joining(File.pathSeparator)));
        }
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = sourceDirectory.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IOException("Compilation failed");
        }

        Files.createDirectories(jar.getParentFile().toPath());
        try (OutputStream out = Files.newOutputStream(jar.toPath()); JarOutputStream jarOut = new JarOutputStream(out);
             Stream<Path> files = Files.walk(classDirectory)) {
            for (Path file : files.filter(Files::isRegularFile).collect(Collectors.toList())) {
                jarOut.putNextEntry(new JarEntry(classDirectory.relativize(file).toString().replace(File.separatorChar, '/')));
                jarOut.write(Files.readAllBytes(file));
                jarOut.closeEntry();
            }
        }
        return jar;
    }

    /**
     * Rewrites every class in a jar to claim another class file version, standing in for classes compiled by a newer JDK
     * than the one running the tests.
     *
     * @param jar          the jar to rewrite
     * @param majorVersion the class file major version, such as 65 for Java 21
     * @return the jar
     * @throws IOException when the jar can't be read or written
     */
    public static File withMajorVersion(File jar, int majorVersion) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> all = jarFile.entries();
            while (all.hasMoreElements()) {
                JarEntry entry = all.nextElement();
                byte[] bytes = jarFile.getInputStream(entry).readAllBytes();
                if (entry.getName().endsWith(".class")) {
                    bytes[6] = (byte) (majorVersion >> 8);
                    bytes[7] = (byte) majorVersion;
                }
                entries.put(entry.getName(), bytes);
            }
        }
        try (OutputStream out = Files.newOutputStream(jar.toPath()); JarOutputStream jarOut = new JarOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                jarOut.putNextEntry(new JarEntry(entry.getKey()));
                jarOut.write(entry.getValue());
                jarOut.closeEntry();
            }
        }
        return jar;
    }
}
