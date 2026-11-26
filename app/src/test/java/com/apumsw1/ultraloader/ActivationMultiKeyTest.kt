package com.apumsw1.ultraloader

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActivationMultiKeyTest {

    private lateinit var context: Context

    private val passThroughCipher = object : ActivationManager.CipherDelegate {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(payload: ByteArray): ByteArray = payload
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ActivationManager.cipherDelegate = passThroughCipher
        ActivationManager.clearAllLicensesForTesting(context)
    }

    @After
    fun tearDown() {
        ActivationManager.clearAllLicensesForTesting(context)
        ActivationManager.resetCipherForProduction()
    }

    @Test
    fun multipleKeysOnSameDeviceStayIndependent() {
        val now = System.currentTimeMillis()
        val key1 = ActivationManager.License(
            key = "UL-KEYONE1111",
            deviceId = "dev-01",
            expiresAt = now + 7 * 86_400_000L,
            status = ActivationManager.STATUS_ACTIVE,
            durationDays = 7,
            paidAmount = 200
        )
        val key2 = ActivationManager.License(
            key = "UL-KEYTWO2222",
            deviceId = "dev-01",
            expiresAt = now + 15 * 86_400_000L,
            status = ActivationManager.STATUS_ACTIVE,
            durationDays = 15,
            paidAmount = 300
        )

        ActivationManager.upsertLicense(context, key1)
        ActivationManager.upsertLicense(context, key2)

        assertTrue(ActivationManager.hasAccess(context))
        val snapshot = ActivationManager.getLicensesSnapshot(context)
        assertEquals(2, snapshot.size)
        assertTrue(ActivationManager.statusText(context).contains("(2 KEYS)"))
        assertTrue(ActivationManager.detailedStatusText(context).contains("#1"))
        assertTrue(ActivationManager.detailedStatusText(context).contains("#2"))
    }

    @Test
    fun expiringOneKeyBlocksOnlyExpiredKeyAndPreservesOtherActiveKey() {
        val now = System.currentTimeMillis()
        ActivationManager.upsertLicense(
            context,
            ActivationManager.License(
                key = "UL-EXPIRED-KEY",
                deviceId = "dev-01",
                expiresAt = now - 60_000L,
                status = ActivationManager.STATUS_ACTIVE,
                durationDays = 1,
                paidAmount = 50
            )
        )
        ActivationManager.upsertLicense(
            context,
            ActivationManager.License(
                key = "UL-VALID-KEY",
                deviceId = "dev-01",
                expiresAt = now + 86_400_000L,
                status = ActivationManager.STATUS_ACTIVE,
                durationDays = 7,
                paidAmount = 200
            )
        )

        // App retains access because UL-VALID-KEY is active.
        assertTrue(ActivationManager.hasAccess(context))

        val snapshot = ActivationManager.getLicensesSnapshot(context)
        val expiredRecord = snapshot.first { it.key == "UL-EXPIRED-KEY" }
        val activeRecord = snapshot.first { it.key == "UL-VALID-KEY" }
        assertEquals(ActivationManager.STATUS_EXPIRED, expiredRecord.status)
        assertEquals(ActivationManager.STATUS_ACTIVE, activeRecord.status)

        // When the second key is also revoked/expired, access is blocked until a new key is activated.
        ActivationManager.markLicenseStatus(context, "UL-VALID-KEY", ActivationManager.STATUS_EXPIRED)
        assertFalse(ActivationManager.hasAccess(context))
        assertEquals("LICENSE: EXPIRED", ActivationManager.statusText(context))

        // Activating a new valid key restores access while preserving historical records.
        ActivationManager.upsertLicense(
            context,
            ActivationManager.License(
                key = "UL-FRESH-KEY",
                deviceId = "dev-01",
                expiresAt = now + 30 * 86_400_000L,
                status = ActivationManager.STATUS_ACTIVE,
                durationDays = 30,
                paidAmount = 400
            )
        )
        assertTrue(ActivationManager.hasAccess(context))
        assertEquals(3, ActivationManager.getLicensesSnapshot(context).size)
    }

    @Test
    fun legacySingleKeyPreferenceMigratesAutomaticallyWithoutDataLoss() {
        val future = System.currentTimeMillis() + 5 * 86_400_000L
        val legacyJson = JSONObject()
            .put("key", "UL-LEGACY-999")
            .put("deviceId", "dev-legacy")
            .put("expiresAt", future)
            .toString()

        context.getSharedPreferences("ultra_loader_activation_v1", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "encrypted_license",
                Base64.encodeToString(legacyJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            )
            .apply()

        assertTrue(ActivationManager.hasAccess(context))
        val snapshot = ActivationManager.getLicensesSnapshot(context)
        assertEquals(1, snapshot.size)
        assertEquals("UL-LEGACY-999", snapshot.first().key)
        assertEquals(ActivationManager.STATUS_ACTIVE, snapshot.first().status)
    }
}
