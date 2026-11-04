import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Isolated Dalvik harness. Never creates the speech application or engine.
 * Run only with the already hash-verified patched ODEX on its classpath.
 * The original ODEX MUST NOT be substituted: these calls would then start engines.
 */
public final class ButtonOnlyOdexProbe {
    private static int checks;

    private static void check(Class<?> type, Object object, String name,
                              Class<?>[] signature, Object[] arguments,
                              Object expected) throws Exception {
        Method method = type.getDeclaredMethod(name, signature);
        Object actual = method.invoke(object, arguments);
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(name + " returned " + actual + ", expected " + expected);
        }
        System.out.println("PASS " + method.toString() + " => " + actual);
        checks++;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1 || !"verified-button-only-candidate".equals(arguments[0])) {
            throw new IllegalArgumentException("Candidate identity must be verified before running");
        }
        System.out.println("PROBE_CLASSPATH=" + System.getProperty("java.class.path"));
        Class<?> type = Class.forName("ma", true, ButtonOnlyOdexProbe.class.getClassLoader());
        System.out.println("PROBE_LOADER=" + type.getClassLoader());
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field singleton;
        try {
            singleton = unsafeType.getDeclaredField("THE_ONE");
        } catch (NoSuchFieldException androidFieldMissing) {
            singleton = unsafeType.getDeclaredField("theUnsafe");
        }
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Object instance = unsafeType.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        // Constructor bypass leaves ma.b null. Any original execution of the
        // seven entrypoints would touch AutoFlyApp/nn or wake engine state;
        // candidate calls return directly without needing those dependencies.
        Field backend = type.getDeclaredField("b");
        backend.setAccessible(true);
        if (backend.get(instance) != null) throw new AssertionError("Constructor unexpectedly ran");
        check(type, instance, "a", new Class<?>[]{String.class, List.class, Boolean.TYPE},
              new Object[]{"CustomIvwThree", new ArrayList<String>(), Boolean.FALSE}, Integer.valueOf(3));
        check(type, instance, "a", new Class<?>[]{String.class, String.class},
              new Object[]{"CustomIvwOne", "[]"}, null);
        check(type, instance, "b", new Class<?>[]{String.class}, new Object[]{"Global"}, null);
        check(type, instance, "c", new Class<?>[]{String.class}, new Object[]{"Global"}, null);
        check(type, instance, "c", new Class<?>[]{}, new Object[]{}, Boolean.FALSE);
        check(type, instance, "d", new Class<?>[]{String.class}, new Object[]{"unused"}, Integer.valueOf(-1));
        check(type, instance, "e", new Class<?>[]{}, new Object[]{}, null);
        if (backend.get(instance) != null) throw new AssertionError("Backend mutated");
        Field instanceField = type.getDeclaredField("a");
        instanceField.setAccessible(true);
        if (instanceField.get(null) != null) throw new AssertionError("Speech singleton unexpectedly initialized");
        System.out.println("BUTTON_ONLY_PROBE_PASS checks=" + checks + " constructor=skipped singleton=null backend=null");
    }
}
