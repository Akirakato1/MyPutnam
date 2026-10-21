package com.putnamworkbook.offline

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.PermissionInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
class PrivacyManifestTest {
    @Test
    fun onlyIntendedNetworkPermissionsAreDeclaredAndCleartextIsDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val receiverPermission = "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"

        assertEquals(
            setOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                receiverPermission,
            ),
            packageInfo.requestedPermissions.orEmpty().toSet(),
        )
        val receiverPermissionInfo = context.packageManager.getPermissionInfo(receiverPermission, 0)
        assertEquals(
            PermissionInfo.PROTECTION_SIGNATURE,
            receiverPermissionInfo.protection,
        )
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0)

        val parser = context.resources.getXml(R.xml.network_security_config)
        try {
            while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
                parser.next()
            }
            assertEquals("network-security-config", parser.name)
        } finally {
            parser.close()
        }
    }

    @Test
    fun packageMetadataMatchesTheV05Contract() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

        assertEquals("MyPutnam", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertEquals("0.5.0", packageInfo.versionName)
        assertEquals(5L, packageInfo.longVersionCode)
    }
}
