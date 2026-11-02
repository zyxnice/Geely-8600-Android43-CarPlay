package com.shilapi.xcertplay.compat;

import android.content.SharedPreferences;
import java.io.IOException;
import java.lang.reflect.Proxy;
import org.junit.Test;
import static org.junit.Assert.*;

/** Persistent budget must survive replacing the connection owner, even after a failed attempt. */
public final class GeelyUsbBootRecoveryBudgetTest {
    private static final String BOOT = "c86838bc-9259-4dd5-a0fb-0cb3754ac636";

    @Test public void sameBootCannotClaimAgainButNewBootCan() throws Exception {
        Store store = new Store();
        GeelyUsbRuntimeRecovery.claimBootRoleRecovery(store.preferences(), BOOT);
        try { GeelyUsbRuntimeRecovery.claimBootRoleRecovery(store.preferences(), BOOT); fail(); }
        catch (IOException expected) { assertEquals("boot_role_recovery_already_attempted", expected.getMessage()); }
        assertEquals(1, store.commits);
        GeelyUsbRuntimeRecovery.claimBootRoleRecovery(store.preferences(), "d86838bc-9259-4dd5-a0fb-0cb3754ac636");
        assertEquals(2, store.commits);
    }

    @Test public void failedDurableCommitRejectsPrivilegedLaunch() throws Exception {
        Store store = new Store(); store.commitSucceeds = false;
        try { GeelyUsbRuntimeRecovery.claimBootRoleRecovery(store.preferences(), BOOT); fail(); }
        catch (IOException expected) { assertEquals("boot_role_attempt_not_saved", expected.getMessage()); }
    }

    @Test public void invalidBootDoesNotWriteBudget() throws Exception {
        Store store = new Store();
        try { GeelyUsbRuntimeRecovery.claimBootRoleRecovery(store.preferences(), "unknown"); fail(); }
        catch (IOException expected) { assertEquals("boot_role_boot_id_invalid", expected.getMessage()); }
        assertEquals(0, store.commits);
    }

    private static final class Store {
        String value, pending;
        int commits;
        boolean commitSucceeds = true;

        SharedPreferences preferences() {
            SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[] {SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if ("putString".equals(method.getName())) { pending = (String) args[1]; return proxy; }
                        if ("commit".equals(method.getName())) {
                            commits++;
                            if (commitSucceeds) value = pending;
                            return commitSucceeds;
                        }
                        throw new AssertionError(method.getName());
                    });
            return (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {SharedPreferences.class}, (proxy, method, args) -> {
                        if ("getString".equals(method.getName())) return value;
                        if ("edit".equals(method.getName())) return editor;
                        throw new AssertionError(method.getName());
                    });
        }
    }
}
