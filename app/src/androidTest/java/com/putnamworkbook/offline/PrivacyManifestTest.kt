package com.putnamworkbook.offline

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {
    @Test
    fun onlyIntendedNetworkPermissionsAreDeclaredAndCleartextIsDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )

        assertEquals(
            setOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE),
            packageInfo.requestedPermissions.orEmpty().toSet(),
        )
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)
    }
}
