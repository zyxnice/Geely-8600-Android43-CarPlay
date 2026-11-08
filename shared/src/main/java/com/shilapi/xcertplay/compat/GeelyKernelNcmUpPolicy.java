package com.shilapi.xcertplay.compat;

import java.io.IOException;

/** Enables only one pinned iPhone NCM enumeration; never disables a NIC or changes IP settings. */
public final class GeelyKernelNcmUpPolicy {
    /** Facts selected by the kernel NCM owner, revalidated by the privileged helper before writing. */
    public static final class Request {
        /** Complete current Apple USB enumeration, including descriptor SHA-256. */
        public final GeelyUsbIdentity usb;
        /** Current Linux interface name; it is not a physical USB-port label. */
        public final String name;
        /** Positive Linux ifindex, fencing replacement of a same-named interface. */
        public final int ifindex;
        /** Six-byte host MAC encoded as 12 lower-case hexadecimal characters. */
        public final String mac;
        /** NCM control bInterfaceNumber in active USB configuration 6. */
        public final int controlInterface;
        /** NCM data bInterfaceNumber in the same configuration. */
        public final int dataInterface;
        /** Selected non-idle alternate of the data interface. */
        public final int dataAlternate;

        public Request(GeelyUsbIdentity usb, String name, int ifindex, String mac,
                int controlInterface, int dataInterface, int dataAlternate) {
            if (usb == null || usb.vendorId != 0x05ac || usb.busNumber != 1
                    || name == null || !name.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,14}")
                    || ifindex <= 0 || mac == null || !mac.matches("[0-9a-f]{12}")
                    || controlInterface < 0 || controlInterface > 255 || dataInterface < 0 || dataInterface > 255
                    || controlInterface == dataInterface || dataAlternate < 1 || dataAlternate > 255)
                throw new IllegalArgumentException("Invalid kernel NCM identity");
            this.usb = usb;
            this.name = name;
            this.ifindex = ifindex;
            this.mac = mac;
            this.controlInterface = controlInterface;
            this.dataInterface = dataInterface;
            this.dataAlternate = dataAlternate;
        }

        /** All attachment facts must still match; a reused netdev name alone is insufficient. */
        public boolean matches(Request other) {
            return other != null && usb.equals(other.usb) && name.equals(other.name) && ifindex == other.ifindex
                    && mac.equals(other.mac) && controlInterface == other.controlInterface
                    && dataInterface == other.dataInterface && dataAlternate == other.dataAlternate;
        }

        String[] arguments() {
            return new String[] {name, usb.sysfsName, Integer.toString(usb.busNumber), Integer.toString(usb.deviceNumber),
                    Integer.toString(usb.vendorId), Integer.toString(usb.productId), usb.descriptorSha256,
                    Integer.toString(ifindex), mac, Integer.toString(controlInterface),
                    Integer.toString(dataInterface), Integer.toString(dataAlternate)};
        }

        static Request parse(String[] args, int offset) {
            if (args.length != offset + 12) throw new IllegalArgumentException("ncm_arguments");
            GeelyUsbIdentity usb = new GeelyUsbIdentity(args[offset + 1], Integer.parseInt(args[offset + 2]),
                    Integer.parseInt(args[offset + 3]), Integer.parseInt(args[offset + 4]),
                    Integer.parseInt(args[offset + 5]), args[offset + 6]);
            return new Request(usb, args[offset], Integer.parseInt(args[offset + 7]), args[offset + 8],
                    Integer.parseInt(args[offset + 9]), Integer.parseInt(args[offset + 10]), Integer.parseInt(args[offset + 11]));
        }
    }

    static final class State {
        final Request identity;
        // Linux IFF_UP is bit 0; carrier/operstate alone is not administrative readiness.
        final int flags;
        State(Request identity, int flags) {
            this.identity = identity;
            this.flags = flags;
        }
        boolean isUp() { return (flags & 1) != 0; }
    }

    interface Port {
        State read() throws Exception;
        void enable(Request expected) throws Exception;
    }

    static boolean usbReady(String role, String charging, String power, String fault) {
        // Runtime preparation preserves an already enumerated config6 device, including SDP.
        // Both SDP and CDP carry data; enabling its NIC must not renegotiate that live USB link.
        return "0".equals(role) && ("sdp".equals(charging) || "cdp".equals(charging))
                && GeelyUsbRuntimePolicy.isPowered(power) && GeelyUsbRuntimePolicy.isHealthy(fault);
    }

    static void requireIdentity(Request expected, State state) throws IOException {
        if (state == null || !expected.matches(state.identity)) throw new IOException("ncm_identity_changed");
    }

    static String enable(Request expected, Port port) throws Exception {
        State before = port.read();
        requireIdentity(expected, before);
        if (before.isUp()) return "already-up";
        State current = port.read();
        requireIdentity(expected, current);
        if (current.isUp()) return "already-up";
        port.enable(expected);
        State after = port.read();
        requireIdentity(expected, after);
        if (!after.isUp()) throw new IOException("ncm_up_unverified");
        // Never roll back by bringing a NIC down: another attachment may already be using it.
        return "enabled";
    }
}
