package com.shilapi.xcertplay.compat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeelyHudExclusivePolicyTest {
    private static final String ID = "10123:1000:2000";

    @Test public void persistsBeforeDisablingAndRestoresExactDefault() throws Exception {
        Fake port = new Fake(0);
        assertEquals("isolated", GeelyHudExclusivePolicy.acquire(port));
        assertEquals("journal", port.events.get(0));
        assertEquals("set:3", port.events.get(1));
        assertEquals("0|" + ID, port.journal);
        assertEquals("restored", GeelyHudExclusivePolicy.restore(port));
        assertEquals(0, port.state.enabled);
        assertNull(port.journal);
    }

    @Test public void explicitEnabledIsNotRestoredAsDefault() throws Exception {
        Fake port = new Fake(1);
        GeelyHudExclusivePolicy.acquire(port);
        GeelyHudExclusivePolicy.restore(port);
        assertEquals(1, port.state.enabled);
    }

    @Test public void uninstalledAndPreDisabledRemainUntouched() throws Exception {
        Fake absent = new Fake(0);
        absent.state = null;
        assertEquals("absent", GeelyHudExclusivePolicy.acquire(absent));
        assertTrue(absent.events.isEmpty());
        for (int state : new int[] {2, 3, 4}) {
            Fake disabled = new Fake(state);
            assertEquals("disabled", GeelyHudExclusivePolicy.acquire(disabled));
            assertEquals("idle", GeelyHudExclusivePolicy.restore(disabled));
            assertTrue(disabled.events.isEmpty());
        }
    }

    @Test public void persistenceFailureNeverDisables() throws Exception {
        Fake port = new Fake(0);
        port.failSave = true;
        try { GeelyHudExclusivePolicy.acquire(port); fail(); } catch (IOException expected) { }
        assertEquals(0, port.state.enabled);
        assertTrue(port.events.isEmpty());
    }

    @Test public void cancellationAfterJournalDoesNotDisable() throws Exception {
        Fake port = new Fake(0);
        port.cancelOnSave = true;
        try { GeelyHudExclusivePolicy.acquire(port); fail(); } catch (IOException expected) { }
        assertEquals(0, port.state.enabled);
        assertEquals("preserved", GeelyHudExclusivePolicy.restore(port));
        assertFalse(port.events.contains("set:3"));
    }

    @Test public void persistedRecordRecoversAfterProcessDeathOrReboot() throws Exception {
        Fake port = new Fake(3);
        port.journal = "1|" + ID;
        port.lease = false;
        assertEquals("restored", GeelyHudExclusivePolicy.restore(port));
        assertEquals(1, port.state.enabled);
    }

    @Test public void reinstallOrExternalStateChangeIsNeverOverwritten() throws Exception {
        for (GeelyHudExclusivePolicy.Snapshot replacement : new GeelyHudExclusivePolicy.Snapshot[] {
                null, new GeelyHudExclusivePolicy.Snapshot("10123:3000:4000", 3, false),
                new GeelyHudExclusivePolicy.Snapshot(ID, 2, false),
                new GeelyHudExclusivePolicy.Snapshot(ID, 1, true)}) {
            Fake port = new Fake(3);
            port.journal = "0|" + ID;
            port.state = replacement;
            assertEquals("preserved", GeelyHudExclusivePolicy.restore(port));
            assertEquals(replacement, port.state);
            assertFalse(port.events.contains("set:0"));
        }
    }

    @Test public void failedRestoreRetainsJournalForRetry() throws Exception {
        Fake port = new Fake(0);
        GeelyHudExclusivePolicy.acquire(port);
        port.ignoreSet = true;
        try { GeelyHudExclusivePolicy.restore(port); fail(); } catch (IOException expected) { }
        assertEquals("0|" + ID, port.journal);
        port.ignoreSet = false;
        assertEquals("restored", GeelyHudExclusivePolicy.restore(port));
    }

    @Test public void partialDisableFailureStillHasRecoveryIntent() throws Exception {
        Fake port = new Fake(0);
        port.throwAfterSet = true;
        try { GeelyHudExclusivePolicy.acquire(port); fail(); } catch (IOException expected) { }
        assertEquals(3, port.state.enabled);
        assertNotNull(port.journal);
        port.throwAfterSet = false;
        GeelyHudExclusivePolicy.restore(port);
        assertEquals(0, port.state.enabled);
    }

    @Test public void invalidJournalDoesNotGuessOriginalState() throws Exception {
        Fake port = new Fake(3);
        port.journal = "0|partial";
        try { GeelyHudExclusivePolicy.restore(port); fail(); } catch (IOException expected) { }
        assertEquals(3, port.state.enabled);
        assertNotNull(port.journal);
    }

    @Test public void leaseRequiresFreshMatchingSessionAndBoot() {
        assertTrue(GeelyHudExclusivePolicy.validLease("nonce|boot|13000", "nonce", "boot", 1000));
        assertFalse(GeelyHudExclusivePolicy.validLease("nonce|boot|13000", "nonce", "boot", 13000));
        assertFalse(GeelyHudExclusivePolicy.validLease("nonce|boot|13000", "new", "boot", 1000));
        assertFalse(GeelyHudExclusivePolicy.validLease("nonce|boot|13000", "nonce", "reboot", 1000));
        assertFalse(GeelyHudExclusivePolicy.validLease("nonce|boot|14000", "nonce", "boot", 1000));
        assertFalse(GeelyHudExclusivePolicy.validLease(null, "nonce", "boot", 1000));
    }

    @Test public void finishedRecoveryRemainsReadableWithoutSettingsPolling() {
        assertEquals("restored", GeelyHudExclusivePolicy.response("nonce|restored|1000", "nonce", 60000));
        assertEquals("idle", GeelyHudExclusivePolicy.response("nonce|idle|1000", "nonce", 60000));
        assertNull(GeelyHudExclusivePolicy.response("old|restored|1000", "new", 60000));
        assertNull(GeelyHudExclusivePolicy.response("nonce|isolated|1000", "nonce", 60000));
        assertEquals("isolated", GeelyHudExclusivePolicy.response("nonce|isolated|1000", "nonce", 2000));
    }

    private static final class Fake implements GeelyHudExclusivePolicy.Port {
        GeelyHudExclusivePolicy.Snapshot state;
        String journal;
        boolean lease = true, failSave, cancelOnSave, ignoreSet, throwAfterSet;
        final List<String> events = new ArrayList<>();
        Fake(int enabled) { state = new GeelyHudExclusivePolicy.Snapshot(ID, enabled, enabled < 2); }
        public GeelyHudExclusivePolicy.Snapshot snapshot() { return state; }
        public String readJournal() { return journal; }
        public void saveJournal(String value) throws IOException {
            if (failSave) throw new IOException("disk_full");
            events.add("journal"); journal = value;
            if (cancelOnSave) lease = false;
        }
        public void clearJournal() { events.add("clear"); journal = null; }
        public void setEnabled(int value) throws IOException {
            events.add("set:" + value);
            if (!ignoreSet) state = new GeelyHudExclusivePolicy.Snapshot(state.identity, value, value < 2);
            if (throwAfterSet) throw new IOException("reply_lost");
        }
        public boolean leased() { return lease; }
    }
}
