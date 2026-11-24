package dev.handypvz.adb

import android.content.Context
import android.os.Build
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * ADB client identity (RSA key + self-signed certificate) persisted in the app's private storage,
 * so the watch only has to be paired once.
 */
class WatchAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val clientKey: PrivateKey
    private val clientCert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(10, TimeUnit.SECONDS)

        val keyFile = File(context.filesDir, KEY_FILE)
        val certFile = File(context.filesDir, CERT_FILE)
        var key: PrivateKey? = null
        var cert: Certificate? = null
        if (keyFile.exists() && certFile.exists()) {
            runCatching {
                key = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
            }
        }
        if (key == null || cert == null) {
            val pair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(2048, SecureRandom())
            }.generateKeyPair()
            key = pair.private
            cert = generateCertificate(pair.public, pair.private)
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeText(toPem(cert!!))
        }
        clientKey = key!!
        clientCert = cert!!
    }

    override fun getPrivateKey(): PrivateKey = clientKey

    override fun getCertificate(): Certificate = clientCert

    override fun getDeviceName(): String = "handyPVZ"

    private fun generateCertificate(
        publicKey: java.security.PublicKey,
        signer: PrivateKey
    ): Certificate {
        val algorithm = "SHA512withRSA"
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + TEN_YEARS_MS)
        val name = X500Name("CN=handyPVZ")

        val extensions = CertificateExtensions().apply {
            set(
                "SubjectKeyIdentifier",
                SubjectKeyIdentifierExtension(KeyIdentifier(publicKey).identifier)
            )
            set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
        }
        val info = X509CertInfo().apply {
            set("version", CertificateVersion(2))
            set("serialNumber", CertificateSerialNumber(SecureRandom().nextInt() and Int.MAX_VALUE))
            set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
            set("subject", CertificateSubjectName(name))
            set("key", CertificateX509Key(publicKey))
            set("validity", CertificateValidity(notBefore, notAfter))
            set("issuer", CertificateIssuerName(name))
            set("extensions", extensions)
        }
        return X509CertImpl(info).also { it.sign(signer, algorithm) }
    }

    private fun toPem(cert: Certificate): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    companion object {
        private const val KEY_FILE = "adb_private.key"
        private const val CERT_FILE = "adb_cert.pem"
        private const val TEN_YEARS_MS = 10L * 365 * 24 * 3600 * 1000

        @Volatile
        private var instance: WatchAdbManager? = null

        fun get(context: Context): WatchAdbManager =
            instance ?: synchronized(this) {
                instance ?: WatchAdbManager(context.applicationContext).also { instance = it }
            }
    }
}
