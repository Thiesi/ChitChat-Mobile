package io.github.thiesi.chitchat.core.push

import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import io.github.thiesi.chitchat.R
import org.junit.Assert.*
import org.junit.Test

/**
 * The real connector on this device: this app's own package (the embedded FCM distributor) is never listed
 * as a delivery app, and Google delivery exists only where Play services is installed. This proves the
 * listing, not that Google registration works: that needs a real device with Play (device acceptance).
 */
class PushDistributorsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun thisAppIsNeverADeliveryAppAndGoogleNeedsPlayServices() {
        val distributors = UnifiedPushDistributors(context)
        assertTrue(distributors.installed().none { it.packageName == context.packageName })
        val playServices = try { context.packageManager.getPackageInfo("com.google.android.gms", 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        val google = distributors.google()
        assertEquals("Google delivery is listed exactly when Play services is installed", playServices, google != null)
        if (google != null) {
            assertEquals(context.packageName, google.packageName)
            assertEquals(context.getString(R.string.push_distributor_google), google.label)
        }
    }
}
