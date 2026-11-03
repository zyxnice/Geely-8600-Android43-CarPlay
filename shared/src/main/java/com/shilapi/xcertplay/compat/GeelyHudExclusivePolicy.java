package com.shilapi.xcertplay.compat;

import java.io.IOException;

/** Fixed-package transaction; durable intent precedes disable, and recovery preserves external changes. */
final class GeelyHudExclusivePolicy {
    static final String PACKAGE = "com.autonavi.amapauto";
    static final long LEASE_MS = 12000;

    interface Port {
        Snapshot snapshot() throws Exception;
        String readJournal() throws Exception;
        void saveJournal(String value) throws Exception;
        void clearJournal() throws Exception;
        void setEnabled(int value) throws Exception;
        boolean leased() throws Exception;
    }

    static final class Snapshot {
        final String identity;
        final int enabled;
        final boolean runnable;
        Snapshot(String identity, int enabled, boolean runnable) {
            this.identity = identity;
            this.enabled = enabled;
            this.runnable = runnable;
        }
    }

    static String acquire(Port port) throws Exception {
        if (port.readJournal() != null) throw new IOException("hud_recovery_pending");
        if (!port.leased()) throw new IOException("hud_lease_expired");
        Snapshot before = port.snapshot();
        if (before == null) return "absent";
        if (!before.runnable || before.enabled == 2 || before.enabled == 3 || before.enabled == 4)
            return "disabled";
        if (before.enabled != 0 && before.enabled != 1) throw new IOException("hud_unknown_state");
        port.saveJournal(before.enabled + "|" + before.identity);
        // Cancellation after durable intent must not issue a delayed disable.
        if (!port.leased()) throw new IOException("hud_lease_expired");
        Snapshot current = port.snapshot();
        if (current == null || !before.identity.equals(current.identity) || before.enabled != current.enabled)
            throw new IOException("hud_package_changed");
        port.setEnabled(3);
        current = port.snapshot();
        if (current == null || !before.identity.equals(current.identity) || current.enabled != 3)
            throw new IOException("hud_disable_unverified");
        return "isolated";
    }

    static String restore(Port port) throws Exception {
        String journal = port.readJournal();
        if (journal == null) return "idle";
        if (!journal.matches("[01]\\|[0-9]+:[0-9]+:[0-9]+")) throw new IOException("hud_journal_invalid");
        int original = journal.charAt(0) - '0';
        String identity = journal.substring(2);
        Snapshot current = port.snapshot();
        if (current == null || !identity.equals(current.identity) || current.enabled != 3) {
            // Uninstall/reinstall, update or another explicit state change cancels our ownership.
            port.clearJournal();
            return "preserved";
        }
        port.setEnabled(original);
        current = port.snapshot();
        if (current == null || !identity.equals(current.identity) || current.enabled != original)
            throw new IOException("hud_restore_unverified");
        port.clearJournal();
        return "restored";
    }

    static boolean validLease(String value, String nonce, String boot, long now) {
        if (value == null) return false;
        String prefix = nonce + "|" + boot + "|";
        if (!value.startsWith(prefix)) return false;
        try {
            long until = Long.parseLong(value.substring(prefix.length()));
            return until > now && until - now <= LEASE_MS;
        } catch (NumberFormatException invalid) { return false; }
    }

    /** Live readiness expires; a matching session's final recovery acknowledgement remains final. */
    static String response(String value, String nonce, long now) {
        if (value == null) return null;
        String[] parts = value.split("\\|");
        if (parts.length != 3 || !parts[0].equals(nonce)) return null;
        try {
            long age = now - Long.parseLong(parts[2]);
            if (age < 0) return null;
            boolean terminal = "restored".equals(parts[1]) || "preserved".equals(parts[1])
                    || "idle".equals(parts[1]) || "failed".equals(parts[1]) || "busy".equals(parts[1]);
            return terminal || age < 3500 ? parts[1] : null;
        } catch (NumberFormatException invalid) { return null; }
    }
}
