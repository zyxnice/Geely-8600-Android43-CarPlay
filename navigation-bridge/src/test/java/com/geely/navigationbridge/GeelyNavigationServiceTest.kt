package com.geely.navigationbridge

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Binder
import android.os.Parcel
import android.os.Process
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise raw IPC parcel layouts recovered from the actual H53 speech client. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeelyNavigationServiceTest {
    private fun service(authorized: Boolean): GeelyNavigationService {
        val service = Robolectric.buildService(GeelyNavigationService::class.java).create().get()
        if (authorized) {
            shadowOf(service.packageManager).setPackagesForUid(
                Process.myUid(), GeelyNavigationService.SPEECH_PACKAGE
            )
        }
        return service
    }

    @Test fun listenerRegistrationSucceedsButEveryBusinessOperationRemainsUnavailable() {
        val service = service(true)
        val binder = service.NavigationBinder()
        for (code in 1..14) {
            val input = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                input.writeInterfaceToken(GeelyNavigationService.DESCRIPTOR)
                when (code) {
                    1 -> input.writeStrongBinder(Binder())
                    2, 3, 4 -> { input.writeString("{}"); input.writeInt(1) }
                    5 -> { input.writeInt(1); input.writeInt(2) }
                    6 -> { input.writeString("{}"); input.writeInt(1); input.writeInt(11) }
                    8 -> { input.writeString("{}"); input.writeInt(1); input.writeString(null) }
                    9, 14 -> Unit
                    else -> input.writeString("{}")
                }
                assertTrue(binder.transact(code, input, reply, 0))
                reply.readException()
                if (code == 14) assertEquals("", reply.readString())
                else assertEquals("transaction $code", if (code == 1) 1 else 0, reply.readInt())
                assertEquals(0, reply.dataAvail())
            } finally {
                input.recycle()
                reply.recycle()
            }
        }
    }

    @Test fun bindingRejectsWrongActionAndUnsupportedPlatform() {
        val service = service(true)
        assertNull(service.onBind(null))
        assertNull(service.onBind(Intent("unrelated.action")))
        // This API28 host is deliberately not the supported H53/API18 vehicle.
        assertNull(service.onBind(Intent(GeelyNavigationService.ACTION)))
    }

    @Test fun h53BindsWithoutDiplayButRefusesInstalledFactoryNavigation() {
        val oldSdk = Build.VERSION.SDK_INT
        val oldBoard = Build.BOARD
        val oldDisplay = Build.DISPLAY
        try {
            val service = service(true)
            // Override platform identity only; Robolectric still runs API28 framework code.
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 18)
            ReflectionHelpers.setStaticField(Build::class.java, "BOARD", "C3ALFUS")
            ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", "06.03.08600.H53.00060")
            assertNotNull(service.onBind(Intent(GeelyNavigationService.ACTION)))
            val factory = PackageInfo().apply {
                packageName = "com.autonavi.amapauto"
                applicationInfo = ApplicationInfo().apply { packageName = factoryPackageName() }
            }
            shadowOf(service.packageManager).installPackage(factory)
            assertNull(service.onBind(Intent(GeelyNavigationService.ACTION)))
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", oldSdk)
            ReflectionHelpers.setStaticField(Build::class.java, "BOARD", oldBoard)
            ReflectionHelpers.setStaticField(Build::class.java, "DISPLAY", oldDisplay)
        }
    }

    private fun factoryPackageName() = "com.autonavi.amapauto"

    @Test fun unrelatedCallerCannotUseTheExportedBinder() {
        val binder = service(false).NavigationBinder()
        val input = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            input.writeInterfaceToken(GeelyNavigationService.DESCRIPTOR)
            input.setDataPosition(0)
            try {
                binder.onTransact(14, input, reply, 0)
                fail("Unrelated caller must be rejected")
            } catch (expected: SecurityException) {
                assertTrue(expected.message!!.contains("speech client"))
            }
        } finally {
            input.recycle()
            reply.recycle()
        }
    }
}
