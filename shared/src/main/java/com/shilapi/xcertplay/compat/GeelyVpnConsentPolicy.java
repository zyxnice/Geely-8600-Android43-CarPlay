package com.shilapi.xcertplay.compat;

import java.io.IOException;

/** H53-only VPN ownership rules; saved trust is never treated as live system permission. */
final class GeelyVpnConsentPolicy {
    static final String PACKAGE = "com.shihab.diplay.repair1.geely8600";
    static final String LEGACY = "[Legacy VPN]";

    /** Mirrors H53 IConnectivityManager.prepareVpn(oldPackage, newPackage). */
    interface Port {
        boolean prepare(String oldPackage, String newPackage) throws Exception;
        boolean hasLegacyVpn() throws Exception;
    }

    private GeelyVpnConsentPolicy() { }

    static boolean validBootId(String value) {
        return value != null && value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    static boolean needsRestore(boolean enabled, boolean trusted, String grantedBoot, String currentBoot) {
        // Same-boot revocation must still require a new system confirmation.
        return enabled && trusted && validBootId(grantedBoot) && validBootId(currentBoot)
                && !grantedBoot.equals(currentBoot);
    }

    static String restore(Port port) throws Exception {
        if (port.prepare(PACKAGE, null)) return "already_prepared";
        // The legacy owner also represents the built-in VPN, not just the boot default.
        // Refuse any existing legacy runner. Its status query and prepare are separate Binder
        // calls on H53: a concurrently starting built-in VPN remains a platform race boundary.
        if (port.hasLegacyVpn()) throw new IOException("vpn_legacy_active");
        // The owner comparison is atomic inside Vpn.prepare and preserves other app owners.
        if (!port.prepare(LEGACY, PACKAGE)) throw new IOException("vpn_owned_elsewhere");
        if (!port.prepare(PACKAGE, null)) throw new IOException("vpn_not_confirmed");
        return "restored";
    }
}
