package com.krakenplugins.gradle.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The classes and members of a Kraken API jar, used to tell whether a reference compiled against one API version still
 * resolves in another. Lookups follow the class hierarchy the way the JVM does: through super classes and interfaces in
 * the jar, and through {@code java.*} classes by reflection. A member that could come from any other class outside the
 * jar, such as a RuneLite class an API class extends, is reported as {@link Resolution#UNKNOWN} rather than missing.
 */
public final class ApiSurface {

    /**
     * The package, in internal form, whose references are checked.
     */
    public static final String API_PACKAGE = "com/kraken/api/";

    /**
     * Whether a reference resolves.
     */
    public enum Resolution {
        FOUND,
        MISSING,
        UNKNOWN
    }

    private final Map<String, ClassFile> classes;

    private ApiSurface(Map<String, ClassFile> classes) {
        this.classes = classes;
    }

    /**
     * Reads every class in the {@link #API_PACKAGE} of a Kraken API jar.
     *
     * @param jar the Kraken API jar
     * @return the surface
     * @throws IOException when the jar can't be read
     */
    public static ApiSurface read(File jar) throws IOException {
        Map<String, ClassFile> classes = new HashMap<>();
        try (JarFile jarFile = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(API_PACKAGE) || !entry.getName().endsWith(".class")) {
                    continue;
                }
                try (InputStream in = jarFile.getInputStream(entry)) {
                    ClassFile classFile = ClassFile.parse(in.readAllBytes());
                    classes.put(classFile.getName(), classFile);
                }
            }
        }
        return new ApiSurface(classes);
    }

    /**
     * @param internalName a class name in internal form
     * @return whether the jar holds the class
     */
    public boolean hasClass(String internalName) {
        return classes.containsKey(internalName);
    }

    /**
     * @return the number of API classes in the jar
     */
    public int size() {
        return classes.size();
    }

    /**
     * Resolves a field or method reference whose owner is an API class.
     *
     * @param reference the reference
     * @return whether the owner, or a class it inherits from, declares the member
     */
    public Resolution resolve(ClassFile.MemberReference reference) {
        if (!classes.containsKey(reference.getOwner())) {
            return Resolution.MISSING;
        }
        String member = reference.getName() + reference.getDescriptor();
        if ("<init>".equals(reference.getName()) || "<clinit>".equals(reference.getName())) {
            return classes.get(reference.getOwner()).getMethods().contains(member) ? Resolution.FOUND : Resolution.MISSING;
        }

        boolean outsideJar = false;
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.add(reference.getOwner());
        while (!pending.isEmpty()) {
            String type = pending.poll();
            if (!visited.add(type)) {
                continue;
            }
            ClassFile classFile = classes.get(type);
            if (classFile == null) {
                Resolution resolution = resolveOutsideJar(type, reference);
                if (resolution == Resolution.FOUND) {
                    return Resolution.FOUND;
                }
                outsideJar |= resolution == Resolution.UNKNOWN;
                continue;
            }
            if ((reference.isField() ? classFile.getFields() : classFile.getMethods()).contains(member)) {
                return Resolution.FOUND;
            }
            pending.addAll(classFile.getInterfaces());
            // Interfaces have Object as their super class in the class file, which is also where the JVM looks last.
            pending.add(classFile.getSuperName() == null ? "java/lang/Object" : classFile.getSuperName());
        }
        return outsideJar ? Resolution.UNKNOWN : Resolution.MISSING;
    }

    /**
     * Looks a member up in a class outside the API jar. JDK classes are checked by reflection, since the JDK the build
     * runs on has the same public members the client's runtime does. Anything else is unknown.
     */
    private static Resolution resolveOutsideJar(String internalName, ClassFile.MemberReference reference) {
        if (!internalName.startsWith("java/")) {
            return Resolution.UNKNOWN;
        }
        Class<?> type;
        try {
            type = Class.forName(internalName.replace('/', '.'), false, null);
        } catch (ClassNotFoundException | LinkageError e) {
            return Resolution.UNKNOWN;
        }
        String member = reference.getName() + reference.getDescriptor();
        Deque<Class<?>> pending = new ArrayDeque<>();
        pending.add(type);
        while (!pending.isEmpty()) {
            Class<?> current = pending.poll();
            if (reference.isField()) {
                for (Field field : current.getDeclaredFields()) {
                    if (member.equals(field.getName() + Descriptors.of(field.getType()))) {
                        return Resolution.FOUND;
                    }
                }
            } else {
                for (Method method : current.getDeclaredMethods()) {
                    if (member.equals(method.getName() + Descriptors.of(method))) {
                        return Resolution.FOUND;
                    }
                }
            }
            if (current.getSuperclass() != null) {
                pending.add(current.getSuperclass());
            }
            for (Class<?> anInterface : current.getInterfaces()) {
                pending.add(anInterface);
            }
        }
        return Resolution.MISSING;
    }

    /**
     * Builds JVM descriptors from reflected types, and turns descriptors back into Java source form for messages.
     */
    public static final class Descriptors {

        private Descriptors() {
        }

        static String of(Class<?> type) {
            if (type.isArray()) {
                return type.getName().replace('.', '/');
            }
            if (!type.isPrimitive()) {
                return "L" + type.getName().replace('.', '/') + ";";
            }
            if (type == void.class) return "V";
            if (type == boolean.class) return "Z";
            if (type == byte.class) return "B";
            if (type == char.class) return "C";
            if (type == short.class) return "S";
            if (type == int.class) return "I";
            if (type == long.class) return "J";
            if (type == float.class) return "F";
            return "D";
        }

        static String of(Executable executable) {
            StringBuilder descriptor = new StringBuilder("(");
            for (Class<?> parameter : executable.getParameterTypes()) {
                descriptor.append(of(parameter));
            }
            descriptor.append(')');
            descriptor.append(executable instanceof Constructor ? "V" : of(((Method) executable).getReturnType()));
            return descriptor.toString();
        }

        /**
         * Describes a reference the way it reads in source, e.g. {@code com.kraken.api.service.bank.BankService.depositAll(int[])}
         * or {@code new com.kraken.api.Foo(java.lang.String)}.
         *
         * @param reference the reference
         * @return the description
         */
        public static String describe(ClassFile.MemberReference reference) {
            String owner = reference.getOwner().replace('/', '.');
            if (reference.isField()) {
                return owner + "." + reference.getName();
            }
            String parameters = parameters(reference.getDescriptor());
            if ("<init>".equals(reference.getName())) {
                return "new " + owner + "(" + parameters + ")";
            }
            return owner + "." + reference.getName() + "(" + parameters + ")";
        }

        private static String parameters(String descriptor) {
            StringBuilder out = new StringBuilder();
            int i = 1;
            while (descriptor.charAt(i) != ')') {
                int dimensions = 0;
                while (descriptor.charAt(i) == '[') {
                    dimensions++;
                    i++;
                }
                String type;
                char code = descriptor.charAt(i);
                if (code == 'L') {
                    int end = descriptor.indexOf(';', i);
                    type = descriptor.substring(i + 1, end).replace('/', '.');
                    i = end + 1;
                } else {
                    type = primitive(code);
                    i++;
                }
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(type).append("[]".repeat(dimensions));
            }
            return out.toString();
        }

        private static String primitive(char code) {
            switch (code) {
                case 'Z': return "boolean";
                case 'B': return "byte";
                case 'C': return "char";
                case 'S': return "short";
                case 'I': return "int";
                case 'J': return "long";
                case 'F': return "float";
                default: return "double";
            }
        }
    }
}
