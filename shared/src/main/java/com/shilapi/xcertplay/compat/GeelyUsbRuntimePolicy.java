package com.shilapi.xcertplay.compat;

import java.io.IOException;

/** Fixed, reversible H53 runtime preparation. No persistent properties or arbitrary paths. */
public final class GeelyUsbRuntimePolicy {
    static final String HOST_CONFIG = "accessory,ncm";
    static final String ADB_CONFIG = "accessory,ncm,adb";

    /** The always-present root-hub interface is not a downstream attachment. */
    static boolean occupiesOtgBus(String sysfsName) {
        return sysfsName.startsWith("1-") && !sysfsName.startsWith("1-0:");
    }

    interface Port {
        State read() throws Exception;
        void configuration(String value) throws Exception;
        void power(String value) throws Exception;
        void charging(String value) throws Exception;
        void awaitHost() throws Exception;
        void beginRollback();
        void awaitRestored(State original) throws Exception;
        void awaitPowerOff() throws Exception;
    }

    static final class State {
        final String config, appliedConfig, role, charging, power, fault;
        final boolean activeApple;
        final boolean busOccupied;
        // busOccupied includes every downstream node on the OTG bus, even an incomplete or
        // non-Apple device. activeApple only describes the existing CarPlay configuration guard.
        State(String config, String appliedConfig, String role, String charging, String power, String fault,
                boolean activeApple, boolean busOccupied) {
            this.config = config; this.appliedConfig = appliedConfig; this.role = role; this.charging = charging;
            this.power = power; this.fault = fault; this.activeApple = activeApple;
            this.busOccupied = busOccupied;
        }
        boolean powered() { return isPowered(power); }
        boolean ready() { return "0".equals(role) && powered() && "sdp".equals(charging) && isHealthy(fault); }
        boolean enumerationReady() { return ready() && HOST_CONFIG.equals(config) && HOST_CONFIG.equals(appliedConfig); }
    }

    /** One empty-discovery recovery per connection owner; a phone appearing restarts the wait. */
    static final class EnumerationWait {
        private long emptySince = -1;
        private boolean attempted;

        void observed(boolean applePresent, long now) {
            if (applePresent) emptySince = -1;
            else if (emptySince < 0) emptySince = now;
        }

        void resetWaiting() { emptySince = -1; }

        boolean claimRecovery(long now) {
            if (attempted || emptySince < 0 || now - emptySince < 2000) return false;
            attempted = true;
            return true;
        }
    }

    /** Only an empty, healthy Host port may be power-cycled; never detach an enumerated device. */
    static String recoverEnumeration(Port port) throws Exception {
        State before = port.read();
        validate(before);
        if (before.busOccupied || before.activeApple) return "device-preserved";
        if (!before.enumerationReady()) return "not-ready";
        boolean powerChanged = false;
        try {
            // Re-read immediately before the disruptive write: enumeration may have completed
            // while the helper was being launched. The kernel offers no atomic test-and-write.
            State current = port.read();
            validate(current);
            if (current.busOccupied || current.activeApple) return "device-preserved";
            if (!current.enumerationReady()) return "not-ready";
            powerChanged = true;
            port.power("off");
            if (port.read().powered()) throw new IOException("runtime_power_off_readback");
            port.awaitPowerOff();
            port.power("on");
            if (!port.read().ready()) throw new IOException("runtime_readback");
            return "enumeration-restarted";
        } catch (Exception failure) {
            // A revoked lease must still allow the bounded restoration of our power-off.
            port.beginRollback();
            try {
                if (powerChanged) {
                    port.power("on");
                    if (!port.read().powered()) throw new IOException("runtime_rollback_readback");
                }
            } catch (Exception rollback) { throw new IOException("runtime_rollback_failed", rollback); }
            throw failure;
        }
    }

    // H53 sysfs readback is descriptive text, not necessarily the value accepted by writes.
    // Keep exact matches: substring matching could accept a fault message containing "on".
    static boolean isPowered(String value) {
        return "on".equals(value) || "normal/on".equals(value) || "normal, power on".equals(value);
    }

    static boolean isHealthy(String value) {
        return "working fine".equals(value) || "working fine.".equals(value);
    }

    static boolean matches(int sdk, String board, String display, boolean optedIn) {
        return optedIn && sdk == 18 && "C3ALFUS".equals(board) && "06.03.08600.H53.00060".equals(display);
    }

    static String prepare(Port port) throws Exception {
        State before = port.read();
        // An already selected CarPlay device may belong to a live session: never reset it.
        if (before.activeApple && "0".equals(before.role)) return "active-preserved";
        validate(before);
        if (before.ready()) return "ready";
        boolean changedConfig = false, changedPower = false, changedCharging = false;
        try {
            if (!"0".equals(before.role) || !HOST_CONFIG.equals(before.config)) {
                changedConfig = true;
                port.configuration(HOST_CONFIG);
                port.awaitHost();
            }
            State host = port.read();
            if (!"0".equals(host.role) || !isHealthy(host.fault)) throw new IOException("host_not_ready");
            if (!host.powered()) { changedPower = true; port.power("on"); }
            if (!"sdp".equals(host.charging)) { changedCharging = true; port.charging("sdp"); }
            if (!port.read().ready()) throw new IOException("runtime_readback");
            return "prepared";
        } catch (Exception failure) {
            // Restore only writes owned by this attempt. A rollback failure remains visible.
            port.beginRollback();
            Exception rollbackFailure = null;
            try { if (changedCharging) port.charging(before.charging); }
            catch (Exception rollback) { rollbackFailure = rollback; }
            try { if (changedPower) port.power(before.powered() ? "on" : "off"); }
            catch (Exception rollback) { rollbackFailure = rollback; }
            try { if (changedConfig) port.configuration(before.config); }
            catch (Exception rollback) { rollbackFailure = rollback; }
            try {
                if (changedConfig) port.awaitRestored(before);
                State restored = port.read();
                if ((changedConfig && (!before.config.equals(restored.config) || !before.role.equals(restored.role)))
                        || (changedPower && before.powered() != restored.powered())
                        || (changedCharging && !before.charging.equals(restored.charging)))
                    throw new IOException("runtime_rollback_readback");
            } catch (Exception rollback) { rollbackFailure = rollback; }
            if (rollbackFailure != null) throw new IOException("runtime_rollback_failed", rollbackFailure);
            throw failure;
        }
    }

    private static void validate(State state) throws IOException {
        if (!HOST_CONFIG.equals(state.config) && !ADB_CONFIG.equals(state.config)) throw new IOException("unexpected_usb_config");
        if (!"0".equals(state.role) && !"1".equals(state.role)) throw new IOException("unexpected_usb_role");
        if (!"sdp".equals(state.charging) && !"cdp".equals(state.charging) && !"dcp".equals(state.charging))
            throw new IOException("unexpected_charging_mode");
        if (!state.powered() && !"off".equals(state.power) && !"normal/off".equals(state.power))
            throw new IOException("unexpected_power_state");
        if (!isHealthy(state.fault)) throw new IOException("otg_power_fault");
    }
}
