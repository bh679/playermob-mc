package games.brennan.playermob.mixin;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks that every mixin in {@code playermob.mixins.json} still has something to attach to in
 * this node's Minecraft: each injector's {@code method} must be <em>declared</em> on the
 * {@code @Mixin} target, and each {@code @Accessor} field must exist there.
 *
 * <p>Mixin only resolves these when the target class loads, and the deobfuscated 26.x build has
 * no refmap step to validate them at compile time — so a method that moved to a superclass
 * between Minecraft versions (26.2 moved {@code usePlayerItem} from {@code Animal} up to
 * {@code Mob}) compiles cleanly and then crashes the game at startup. Reading the class files
 * directly keeps this independent of a running Mixin environment.</p>
 */
class MixinTargetsTest {

    private static final String CONFIG = "playermob.mixins.json";
    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String INJECTOR_PACKAGE = "Lorg/spongepowered/asm/mixin/injection/";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
    private static final List<String> CONFIG_LISTS = List.of("mixins", "client", "server");
    /** Byte offset of the big-endian major version in a class file (after magic + minor). */
    private static final int MAJOR_VERSION_OFFSET = 6;
    /** Java 17 — the lowest level any node targets, so every node's ASM reads it. */
    private static final int READABLE_MAJOR_VERSION = 61;

    @Test
    void everyMixinMemberResolvesOnItsTarget() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String mixinName : configuredMixins()) {
            ClassNode mixin = readClass(mixinName.replace('.', '/'));
            List<ClassNode> targets = targetsOf(mixin);
            if (targets.isEmpty()) {
                problems.add(mixinName + ": no @Mixin target found");
            }
            for (MethodNode handler : mixin.methods) {
                for (AnnotationNode annotation : annotationsOf(handler)) {
                    for (ClassNode target : targets) {
                        checked += check(mixinName, handler, annotation, target, problems);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), "Unresolvable mixin members:\n" + String.join("\n", problems));
        assertTrue(checked > 0, "No injector or accessor was checked — is " + CONFIG + " being read?");
    }

    /** @return how many references this annotation contributed, appending any that don't resolve */
    private static int check(String mixinName, MethodNode handler, AnnotationNode annotation,
                             ClassNode target, List<String> problems) {
        String where = mixinName + "#" + handler.name + " -> " + target.name;
        if (annotation.desc.equals(ACCESSOR_DESC)) {
            String field = (String) valueOf(annotation, "value");
            if (field == null || field.isEmpty()) {
                return 0; // name inferred from the accessor method — nothing explicit to check
            }
            if (target.fields.stream().noneMatch(f -> f.name.equals(field))) {
                problems.add(where + ": no field '" + field + "'");
            }
            return 1;
        }
        List<String> references = new ArrayList<>();
        if (annotation.desc.equals(INVOKER_DESC)) {
            String method = (String) valueOf(annotation, "value");
            if (method != null && !method.isEmpty()) {
                references.add(method);
            }
        } else if (annotation.desc.startsWith(INJECTOR_PACKAGE)) {
            Object methods = valueOf(annotation, "method");
            if (methods instanceof List<?> list) {
                list.forEach(m -> references.add((String) m));
            }
        }
        for (String reference : references) {
            if (!declares(target, reference)) {
                problems.add(where + ": no method '" + reference + "' declared on the target");
            }
        }
        return references.size();
    }

    /** Matches a Mixin method reference: {@code name}, {@code name*} or {@code name(desc)ret}. */
    private static boolean declares(ClassNode target, String reference) {
        int descStart = reference.indexOf('(');
        String name = descStart < 0 ? reference : reference.substring(0, descStart);
        String desc = descStart < 0 ? null : reference.substring(descStart);
        boolean prefix = name.endsWith("*");
        String bareName = prefix ? name.substring(0, name.length() - 1) : name;
        return target.methods.stream().anyMatch(m ->
            (prefix ? m.name.startsWith(bareName) : m.name.equals(bareName))
                && (desc == null || m.desc.equals(desc)));
    }

    private static List<String> configuredMixins() throws IOException {
        try (InputStream in = resource(CONFIG)) {
            JsonObject config = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            String pkg = config.get("package").getAsString();
            List<String> names = new ArrayList<>();
            for (String list : CONFIG_LISTS) {
                if (!config.has(list)) {
                    continue;
                }
                for (JsonElement entry : config.getAsJsonArray(list)) {
                    names.add(pkg + "." + entry.getAsString());
                }
            }
            return names;
        }
    }

    private static List<ClassNode> targetsOf(ClassNode mixin) throws IOException {
        List<ClassNode> targets = new ArrayList<>();
        for (AnnotationNode annotation : nullToEmpty(mixin.invisibleAnnotations)) {
            if (!annotation.desc.equals(MIXIN_DESC)) {
                continue;
            }
            if (valueOf(annotation, "value") instanceof List<?> classes) {
                for (Object type : classes) {
                    targets.add(readClass(((Type) type).getInternalName()));
                }
            }
            if (valueOf(annotation, "targets") instanceof List<?> names) {
                for (Object name : names) {
                    targets.add(readClass(((String) name).replace('.', '/')));
                }
            }
        }
        return targets;
    }

    private static List<AnnotationNode> annotationsOf(MethodNode method) {
        List<AnnotationNode> all = new ArrayList<>(nullToEmpty(method.visibleAnnotations));
        all.addAll(nullToEmpty(method.invisibleAnnotations));
        return all;
    }

    /** ASM stores annotation values as a flat {@code [name, value, name, value, …]} list. */
    private static Object valueOf(AnnotationNode annotation, String key) {
        List<Object> values = nullToEmpty(annotation.values);
        for (int i = 0; i + 1 < values.size(); i += 2) {
            if (key.equals(values.get(i))) {
                return values.get(i + 1);
            }
        }
        return null;
    }

    private static ClassNode readClass(String internalName) throws IOException {
        try (InputStream in = resource(internalName + ".class")) {
            ClassNode node = new ClassNode();
            new ClassReader(readableBy(in.readAllBytes()))
                .accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
            return node;
        }
    }

    /**
     * The ASM on the test classpath can be older than this node's class files (26.x compiles to
     * Java 25) and refuses a major version it doesn't know. Only names, descriptors and
     * annotations are read here, and their layout hasn't changed, so present a newer class as
     * the oldest version every node's ASM accepts.
     */
    private static byte[] readableBy(byte[] classFile) {
        int major = ((classFile[MAJOR_VERSION_OFFSET] & 0xFF) << 8)
            | (classFile[MAJOR_VERSION_OFFSET + 1] & 0xFF);
        if (major <= READABLE_MAJOR_VERSION) {
            return classFile;
        }
        byte[] copy = classFile.clone();
        copy[MAJOR_VERSION_OFFSET] = (byte) (READABLE_MAJOR_VERSION >> 8);
        copy[MAJOR_VERSION_OFFSET + 1] = (byte) READABLE_MAJOR_VERSION;
        return copy;
    }

    private static InputStream resource(String path) throws IOException {
        InputStream in = MixinTargetsTest.class.getClassLoader().getResourceAsStream(path);
        if (in == null) {
            throw new IOException("Not on the test classpath: " + path);
        }
        return in;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
