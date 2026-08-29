package com.ipcamera.videoserver.tls

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal

@Singleton
class TlsCertManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private companion object {
        const val KEY_ALIAS = "camera_server_tls"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

    private fun ensureKeyExists() {
        if (keyStore.containsAlias(KEY_ALIAS)) return
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=IP Camera Server"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Date(0))
            .setCertificateNotAfter(Date(Long.MAX_VALUE / 2))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE_PROVIDER)
            .also { it.initialize(spec) }
            .generateKeyPair()
        keyStore.load(null) // reload to see the new entry
    }

    fun serverSocketFactory(): SSLServerSocketFactory {
        ensureKeyExists()
        val kmf = KeyManagerFactory.getInstance("PKIX")
        kmf.init(keyStore, null)
        val sslCtx = SSLContext.getInstance("TLS")
        sslCtx.init(kmf.keyManagers, null, SecureRandom())
        return sslCtx.serverSocketFactory as SSLServerSocketFactory
    }

    fun fingerprint(): String {
        ensureKeyExists()
        val cert = keyStore.getCertificate(KEY_ALIAS) ?: return "unavailable"
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }
}
