package org.mozilla.javascript.lc;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author ZZZank
 */
public abstract class MemberCollector {
    public static MemberSnapShot<Method> collectMethods(
            Class<?> clazz, boolean includeProtected, boolean includePrivate) {
        var result = new MemberSnapShot<Method>();

        if (Modifier.isPublic(clazz.getModifiers())
                && ReflectUtils.isExportedClass(clazz)
                && !includeProtected
                && !includePrivate) {
            // best case: we have public access and only need public access
            for (var method : clazz.getMethods()) {
                result.selectMap(Modifier.isStatic(method.getModifiers()))
                        .computeIfAbsent(method.getName(), k -> new ArrayList<>())
                        .add(method);
            }
            return result;
        }

        // in all other cases, Rhino either needs more than public access,
        // or there's no public access

        var parentClasses = new LinkedHashSet<Class<?>>();
        fillInheritance(parentClasses, clazz);

        for (var parent : parentClasses) {
            if (!ReflectUtils.isExportedClass(parent)) {
                continue;
            }

            Method[] methods;
            try {
                methods = parent.getDeclaredMethods();
            } catch (SecurityException | NoClassDefFoundError e) {
                // SecurityException: SecurityManager does not allow accessing it, or
                // NoClassDefFoundError: Some classes in parameter types or return type does not
                // exist at runtime
                continue;
            }

            for (var method : methods) {
                if (method.isSynthetic()
                        || !visibleByAccessModifier(method, includeProtected, includePrivate)) {
                    continue;
                }

                result.selectMap(Modifier.isStatic(method.getModifiers()))
                        .computeIfAbsent(method.getName(), k -> new ArrayList<>())
                        .add(method);
                // we can ensure now, for added methods:
                // - their declaring classes are exported (or the java is not modular)
                // - they meet access modifier requirement
                // the only remaining question is whether the declaring class is public. But the
                // principle is "use accessible one whenever possible", not "only use accessible
                // one", so check will be deferred to LazyJavaMember.resolve(), where it will try
                // looking for public one, and try making it accessible when none is public
            }
        }

        return result;
    }

    public static final class MemberSnapShot<T extends Member> {
        public final Map<String, List<T>> instanceMembers = new HashMap<>();
        public final Map<String, List<T>> staticMembers = new HashMap<>();

        public Map<String, List<T>> selectMap(boolean isStatic) {
            return isStatic ? staticMembers : instanceMembers;
        }
    }

    private static boolean visibleByAccessModifier(
            Method method, boolean includeProtected, boolean includePrivate) {
        if (includePrivate) {
            return true;
        }
        int mods = method.getModifiers();
        if (includeProtected) {
            return Modifier.isPublic(mods) || Modifier.isProtected(mods);
        }
        return Modifier.isPublic(mods);
    }

    /**
     * The resulting insertion order: superclasses inserted earlier than interfaces, and direct
     * superclass inserted earlier than indirect superclasses.
     *
     * <p>A class might get inserted multiple times. Insertion order above only describes first
     * insertion
     */
    static void fillInheritance(Set<Class<?>> result, Class<?> current) {
        result.add(current);
        if (current.getSuperclass() != null) {
            fillInheritance(result, current.getSuperclass());
        }
        for (var anInterface : current.getInterfaces()) {
            fillInheritance(result, anInterface);
        }
    }
}
