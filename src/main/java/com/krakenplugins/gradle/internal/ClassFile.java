package com.krakenplugins.gradle.internal;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The parts of a class file that the compatibility check needs: its name, super types and declared members, the
 * annotations on the class, and the classes, fields and methods its constant pool refers to. Names use the internal
 * form ({@code com/kraken/api/Context}).
 */
public final class ClassFile {

    private static final int MAGIC = 0xCAFEBABE;
    private static final int UTF8 = 1;
    private static final int INTEGER = 3;
    private static final int FLOAT = 4;
    private static final int LONG = 5;
    private static final int DOUBLE = 6;
    private static final int CLASS = 7;
    private static final int STRING = 8;
    private static final int FIELD_REF = 9;
    private static final int METHOD_REF = 10;
    private static final int INTERFACE_METHOD_REF = 11;
    private static final int NAME_AND_TYPE = 12;
    private static final int METHOD_HANDLE = 15;
    private static final int METHOD_TYPE = 16;
    private static final int DYNAMIC = 17;
    private static final int INVOKE_DYNAMIC = 18;
    private static final int MODULE = 19;
    private static final int PACKAGE = 20;
    private static final String RUNTIME_VISIBLE_ANNOTATIONS = "RuntimeVisibleAnnotations";

    private final int majorVersion;
    private final String name;
    private final String superName;
    private final List<String> interfaces;
    private final Set<String> fields;
    private final Set<String> methods;
    private final Set<String> annotations;
    private final Set<String> classReferences;
    private final List<MemberReference> memberReferences;

    private ClassFile(int majorVersion, String name, String superName, List<String> interfaces, Set<String> fields,
                      Set<String> methods, Set<String> annotations, Set<String> classReferences,
                      List<MemberReference> memberReferences) {
        this.majorVersion = majorVersion;
        this.name = name;
        this.superName = superName;
        this.interfaces = Collections.unmodifiableList(interfaces);
        this.fields = Collections.unmodifiableSet(fields);
        this.methods = Collections.unmodifiableSet(methods);
        this.annotations = Collections.unmodifiableSet(annotations);
        this.classReferences = Collections.unmodifiableSet(classReferences);
        this.memberReferences = Collections.unmodifiableList(memberReferences);
    }

    /**
     * Parses a class file.
     *
     * @param bytes the class file's bytes
     * @return the parsed class
     * @throws IOException when the bytes are not a class file
     */
    public static ClassFile parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != MAGIC) {
            throw new IOException("Not a class file");
        }
        in.readUnsignedShort();
        int majorVersion = in.readUnsignedShort();

        int poolSize = in.readUnsignedShort();
        int[] tags = new int[poolSize];
        String[] utf8 = new String[poolSize];
        int[] first = new int[poolSize];
        int[] second = new int[poolSize];
        for (int i = 1; i < poolSize; i++) {
            int tag = in.readUnsignedByte();
            tags[i] = tag;
            switch (tag) {
                case UTF8:
                    utf8[i] = in.readUTF();
                    break;
                case INTEGER:
                case FLOAT:
                    in.readInt();
                    break;
                case LONG:
                case DOUBLE:
                    in.readLong();
                    i++;
                    break;
                case CLASS:
                case STRING:
                case METHOD_TYPE:
                case MODULE:
                case PACKAGE:
                    first[i] = in.readUnsignedShort();
                    break;
                case FIELD_REF:
                case METHOD_REF:
                case INTERFACE_METHOD_REF:
                case NAME_AND_TYPE:
                case DYNAMIC:
                case INVOKE_DYNAMIC:
                    first[i] = in.readUnsignedShort();
                    second[i] = in.readUnsignedShort();
                    break;
                case METHOD_HANDLE:
                    in.readUnsignedByte();
                    first[i] = in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("Unknown constant pool tag " + tag);
            }
        }

        Set<String> classReferences = new LinkedHashSet<>();
        List<MemberReference> memberReferences = new ArrayList<>();
        for (int i = 1; i < poolSize; i++) {
            if (tags[i] == CLASS) {
                classReferences.add(utf8[first[i]]);
            } else if (tags[i] == FIELD_REF || tags[i] == METHOD_REF || tags[i] == INTERFACE_METHOD_REF) {
                String owner = utf8[first[first[i]]];
                int nameAndType = second[i];
                memberReferences.add(new MemberReference(tags[i] == FIELD_REF, owner,
                        utf8[first[nameAndType]], utf8[second[nameAndType]]));
            }
        }

        in.readUnsignedShort();
        String name = utf8[first[in.readUnsignedShort()]];
        int superIndex = in.readUnsignedShort();
        String superName = superIndex == 0 ? null : utf8[first[superIndex]];
        int interfaceCount = in.readUnsignedShort();
        List<String> interfaces = new ArrayList<>(interfaceCount);
        for (int i = 0; i < interfaceCount; i++) {
            interfaces.add(utf8[first[in.readUnsignedShort()]]);
        }

        Set<String> fields = readMembers(in, utf8);
        Set<String> methods = readMembers(in, utf8);

        Set<String> annotations = new LinkedHashSet<>();
        int attributeCount = in.readUnsignedShort();
        for (int i = 0; i < attributeCount; i++) {
            String attributeName = utf8[in.readUnsignedShort()];
            int length = in.readInt();
            if (RUNTIME_VISIBLE_ANNOTATIONS.equals(attributeName)) {
                byte[] attribute = new byte[length];
                in.readFully(attribute);
                readAnnotationTypes(attribute, utf8, annotations);
            } else {
                in.skipBytes(length);
            }
        }

        return new ClassFile(majorVersion, name, superName, interfaces, fields, methods, annotations, classReferences,
                memberReferences);
    }

    /**
     * Reads a fields or methods table into {@code name + descriptor} keys, such as {@code players()Lcom/kraken/api/query/player/PlayerQuery;}.
     */
    private static Set<String> readMembers(DataInputStream in, String[] utf8) throws IOException {
        int count = in.readUnsignedShort();
        Set<String> members = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) {
            in.readUnsignedShort();
            String memberName = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            members.add(memberName + descriptor);
            int attributeCount = in.readUnsignedShort();
            for (int a = 0; a < attributeCount; a++) {
                in.readUnsignedShort();
                in.skipBytes(in.readInt());
            }
        }
        return members;
    }

    /**
     * Reads the type of each top-level annotation, ignoring their element values beyond what's needed to skip them.
     */
    private static void readAnnotationTypes(byte[] attribute, String[] utf8, Set<String> annotations) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(attribute));
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String descriptor = utf8[in.readUnsignedShort()];
            annotations.add(descriptor.substring(1, descriptor.length() - 1));
            skipElementValuePairs(in);
        }
    }

    private static void skipElementValuePairs(DataInputStream in) throws IOException {
        int pairs = in.readUnsignedShort();
        for (int i = 0; i < pairs; i++) {
            in.readUnsignedShort();
            skipElementValue(in);
        }
    }

    private static void skipElementValue(DataInputStream in) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'e':
                in.readUnsignedShort();
                in.readUnsignedShort();
                break;
            case '@':
                in.readUnsignedShort();
                skipElementValuePairs(in);
                break;
            case '[':
                int values = in.readUnsignedShort();
                for (int i = 0; i < values; i++) {
                    skipElementValue(in);
                }
                break;
            default:
                in.readUnsignedShort();
                break;
        }
    }

    /**
     * @return the class file's major version: 52 for Java 8, 55 for Java 11, 61 for Java 17
     */
    public int getMajorVersion() {
        return majorVersion;
    }

    public String getName() {
        return name;
    }

    /**
     * @return the super class, or {@code null} for {@code java/lang/Object}
     */
    public String getSuperName() {
        return superName;
    }

    public List<String> getInterfaces() {
        return interfaces;
    }

    /**
     * @return declared fields as {@code name + descriptor}
     */
    public Set<String> getFields() {
        return fields;
    }

    /**
     * @return declared methods and constructors as {@code name + descriptor}
     */
    public Set<String> getMethods() {
        return methods;
    }

    /**
     * @return the types of the class's runtime-visible annotations
     */
    public Set<String> getAnnotations() {
        return annotations;
    }

    /**
     * @return every class the constant pool names, including array descriptors such as {@code [Ljava/lang/String;}
     */
    public Set<String> getClassReferences() {
        return classReferences;
    }

    /**
     * @return every field and method the constant pool refers to
     */
    public List<MemberReference> getMemberReferences() {
        return memberReferences;
    }

    /**
     * A field or method reference from the constant pool.
     */
    public static final class MemberReference {

        private final boolean field;
        private final String owner;
        private final String name;
        private final String descriptor;

        MemberReference(boolean field, String owner, String name, String descriptor) {
            this.field = field;
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        public boolean isField() {
            return field;
        }

        public String getOwner() {
            return owner;
        }

        public String getName() {
            return name;
        }

        public String getDescriptor() {
            return descriptor;
        }
    }
}
