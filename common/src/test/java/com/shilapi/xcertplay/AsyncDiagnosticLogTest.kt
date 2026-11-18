package com.shilapi.xcertplay

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AsyncDiagnosticLogTest {
    @Test fun longAacWindowsKeepEveryFieldAndStreamIdentityInBoundedFileParts() {
        val folder = Files.createTempDirectory("diplay-aac-log").toFile()
        val target = SessionLogFile(folder.resolve("aac.log"))
        val fields = (0..79).map { "metric$it=${Long.MAX_VALUE - it}" }
        try {
            target.reset("session")
            AsyncDiagnosticLog.appendAac(target,
                "AAC: stream=abc event=window window=7 " + fields.joinToString(" "))
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val parts = target.file.readLines().filter { it.contains("AAC: ") }
            assertTrue(parts.size > 1)
            assertTrue(parts.all { it.length < 700 })
            parts.forEachIndexed { index, line ->
                assertTrue(line.contains("AAC: stream=abc event=window window=7 part=${index + 1}/${parts.size} "))
            }
            val actual = parts.flatMap { it.substringAfter(Regex("part=[0-9]+/[0-9]+ ").find(it)!!.value).split(' ') }
            assertEquals(fields, actual)
        } finally {
            target.close()
            folder.deleteRecursively()
        }
    }

    @Test fun queuedEvidenceIsRedactedAndTimestampedBeforeUsingItsCapturedFileTarget() {
        val folder = Files.createTempDirectory("diplay-async-log").toFile()
        val old = SessionLogFile(folder.resolve("old.log"))
        val current = SessionLogFile(folder.resolve("current.log"))
        try {
            old.reset("old session"); current.reset("current session")
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC attempt=1 teardown end elapsedMs=117", 0)
            AsyncDiagnosticLog.append(old, "Microphone: start type=telephony codec=OPUS peer=192.168.49.1", 0)
            AsyncDiagnosticLog.append(old, "CONNECTION_DIAGNOSTIC token=private-token", 0)
            AsyncDiagnosticLog.append(old, "Microphone: payload=private-audio", 0)
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            val evidence = old.file.readText()
            assertTrue(evidence.contains("teardown end elapsedMs=117"))
            assertTrue(evidence.contains("Microphone: start type=telephony codec=OPUS peer=[ip]"))
            assertFalse(evidence.contains("192.168.49.1"))
            assertFalse(evidence.contains("private-token")); assertFalse(evidence.contains("private-audio"))
            assertFalse(current.file.readText().contains("teardown end"))
            assertEquals(4, evidence.lineSequence().count())
        } finally {
            old.close(); current.close(); folder.deleteRecursively()
        }
    }
}
