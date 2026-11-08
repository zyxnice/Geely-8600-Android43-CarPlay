package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.compat.GeelyUsbDescriptorParser
import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Virtual sysfs lengths must neither cause large preallocation nor truncate descriptor identity. */
class GeelyKernelNcmReadTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun scalarReadsTrimWhitespaceAndRejectEmptyOrOversizedValues() {
        val file = temporary.newFile()
        file.writeText(" 1\n")
        assertEquals("1", readKernelNcmScalar(file))
        for (value in listOf("", "x".repeat(128))) {
            file.writeText(value)
            try { readKernelNcmScalar(file); fail("Invalid sysfs scalar must fail closed") }
            catch (_: IOException) { }
        }
    }

    @Test
    fun completeDescriptorsUseTheStreamInsteadOfVirtualFileLength() {
        val file = temporary.newFile()
        val bytes = ByteArray(6000) { it.toByte() }
        file.writeBytes(bytes)
        val sysfsLength = object : File(file.path) {
            override fun length() = 65_553L
        }
        assertArrayEquals(bytes, readKernelNcmDescriptors(sysfsLength))
    }

    @Test
    fun oversizedDescriptorsRetainTheExistingDiscoveryLimit() {
        val file = temporary.newFile()
        file.writeBytes(ByteArray(GeelyUsbDescriptorParser.MAX_DESCRIPTOR_BYTES + 1))
        try { readKernelNcmDescriptors(file); fail("Must preserve the full-descriptor bound") }
        catch (_: IOException) { }
    }
}
