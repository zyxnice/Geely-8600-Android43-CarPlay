package com.shilapi.xcertplay.compat;

/**
 * Build-time diagnostic gate. Ordinary builds always retain diagnostics. Only the
 * explicit Wi-Fi quiet candidate's R8 rules replace this method with false, across
 * library boundaries; no preferences or runtime state can change playback behaviour.
 */
public final class RuntimeDiagnostics {
    private RuntimeDiagnostics() {}

    public static boolean enabled() {
        return true;
    }
}
