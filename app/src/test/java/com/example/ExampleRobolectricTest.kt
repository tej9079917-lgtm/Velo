package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.browser.AdBlockerEngine
import com.example.crypto.E2EECryptoEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Velo Browser", appName)
    }

    @Test
    fun `e2ee crypto engine encrypts and decrypts payload`() {
        val bundle = E2EECryptoEngine.createNewVault("Velo#StrongPass2026!")
        val secret = "sensitive-token-payload"
        val envelope = E2EECryptoEngine.encrypt(secret, bundle.derivedKey)
        val unlocked = E2EECryptoEngine.unlockVault(
            passphrase = "Velo#StrongPass2026!",
            saltBase64 = bundle.saltBase64,
            verifierCiphertext = bundle.verifierCiphertext,
            verifierIv = bundle.verifierIv,
            iterations = bundle.kdfIterations
        )
        assertNotNull(unlocked)
        val restored = E2EECryptoEngine.decrypt(envelope.ciphertextBase64, envelope.ivBase64, unlocked!!)
        assertEquals(secret, restored)
    }

    @Test
    fun `ad blocker intercepts known tracker and allows normal content`() {
        assertTrue(
            AdBlockerEngine.shouldBlock(
                requestUrl = "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js",
                pageHost = "example.org",
                adBlockEnabled = true
            )
        )
        assertFalse(
            AdBlockerEngine.shouldBlock(
                requestUrl = "https://en.wikipedia.org/wiki/Privacy",
                pageHost = "en.wikipedia.org",
                adBlockEnabled = true
            )
        )
    }
}
