# Applied ONLY to the explicitly selected, optimized H53 Wi-Fi quiet candidate.
# Do not match Object.* or arbitrary callbacks: status/transport callbacks are functional.
-assumevalues class com.shilapi.xcertplay.compat.RuntimeDiagnostics {
    public static boolean enabled() return false;
}
-assumenosideeffects class android.util.Log {
    public static int v(...) return 0;
    public static int d(...) return 0;
    public static int i(...) return 0;
    public static int w(...) return 0;
    public static int e(...) return 0;
    public static int wtf(...) return 0;
    public static int println(...) return 0;
}
-assumevalues class android.util.Log {
    public static boolean isLoggable(...) return false;
}
# JmDNS uses java.util.logging. Do not strip stream output used by helper protocols.
-assumenosideeffects class java.util.logging.Logger {
    public void log(...);
    public void logp(...);
    public void logrb(...);
    public void finest(...);
    public void finer(...);
    public void fine(...);
    public void config(...);
    public void info(...);
    public void warning(...);
    public void severe(...);
    public void entering(...);
    public void exiting(...);
    public void throwing(...);
}
-assumevalues class java.util.logging.Logger {
    public boolean isLoggable(...) return false;
}
# SLF4J's internal diagnostics go directly to stderr.
-assumenosideeffects class org.slf4j.helpers.Util {
    public static void report(...);
}
