package org.winlogon.minechat

import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

import java.io.FileInputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Generates self-signed TLS certificates.
 *
 * This removes the dependency on the `keytool` CLI, allowing the plugin
 * to generate certificates programmatically on first startup.
 *
 * The generated certificates include proper X.509 extensions for TLS compatibility:
 * - Subject Alternative Name (SAN)
 * - Basic Constraints (CA=false)
 * - Key Usage (digitalSignature, keyEncipherment)
 * - Extended Key Usage (serverAuth)
 */
object CertificateGenerator {

    private val BC_PROVIDER: Provider by lazy {
        if (Security.getProvider("BC") == null) {
            BouncyCastleProvider()
        } else {
            Security.getProvider("BC")
        }
    }

    init {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BC_PROVIDER)
        }
    }

    /**
     * Generates a self-signed TLS certificate and stores it in a PKCS#12 keystore.
     *
     * @param keystorePath Path to store the keystore file
     * @param keystorePassword Password for the keystore
     * @param keyAlias Alias for the key entry in the keystore (default: minechat)
     * @param validityDays Validity period in days (default: 365)
     * @param commonName Common Name for the certificate (default: "MineChat Server")
     * @param hostname Hostname for Subject Alternative Name (default: "localhost")
     * @return The generated KeyStore
     */
    fun generateKeystore(
        keystorePath: Path,
        keystorePassword: CharArray,
        keyAlias: String = "minechat",
        validityDays: Int = 365,
        commonName: String = "MineChat Server",
        hostname: String = "localhost"
    ): KeyStore {
        val keyPair = generateKeyPair()
        val certificate = generateCertificate(keyPair, commonName, hostname, validityDays)

        val keyStore = KeyStore.getInstance("PKCS12", BC_PROVIDER)
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            keyAlias,
            keyPair.private,
            keystorePassword,
            arrayOf(certificate)
        )

        keystorePath.parent?.toFile()?.mkdirs()

        FileOutputStream(keystorePath).use { fos ->
            keyStore.store(fos, keystorePassword)
        }

        setPrivateFilePermissions(keystorePath)

        return keyStore
    }

    /**
     * Loads an existing keystore from disk.
     *
     * @param keystorePath Path to the keystore file
     * @param keystorePassword Password for the keystore
     * @return The loaded KeyStore, or null if the file doesn't exist
     */
    fun loadKeystore(keystorePath: Path, keystorePassword: CharArray): KeyStore? {
        if (!keystorePath.toFile().exists()) {
            return null
        }

        return KeyStore.getInstance("PKCS12", BC_PROVIDER).apply {
            FileInputStream(keystorePath.toFile()).use { fis ->
                load(fis, keystorePassword)
            }
        }
    }

    private fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance("EC", "BC")
        keyGen.initialize(256, SecureRandom())
        return keyGen.generateKeyPair()
    }

    private fun generateCertificate(
        keyPair: KeyPair,
        commonName: String,
        hostname: String,
        validityDays: Int
    ): X509Certificate {
        val startDate = Date()
        val endDate = Date(startDate.time + validityDays.toLong() * 24 * 60 * 60 * 1000)

        val subject = X500Principal("CN=$commonName")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(160, SecureRandom()),
            startDate,
            endDate,
            subject,
            keyPair.public
        )

        certBuilder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(GeneralName(GeneralName.dNSName, hostname))
        )

        certBuilder.addExtension(
            Extension.basicConstraints,
            false,
            byteArrayOf(0x30, 0x00)
        )

        certBuilder.addExtension(
            Extension.keyUsage,
            false,
            DERBitString(byteArrayOf(0x80.toByte(), 0x00))
        )

        certBuilder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(
                KeyPurposeId.id_kp_serverAuth
            )
        )

        val signer: ContentSigner = JcaContentSignerBuilder("SHA256WithECDSA")
            .setProvider(BC_PROVIDER)
            .build(keyPair.private)

        return JcaX509CertificateConverter()
            .setProvider(BC_PROVIDER)
            .getCertificate(certBuilder.build(signer))
    }

    /**
     * Changes the permissions on POSIX-compliant OSes to be `600`
     *
     * @param path The path to apply the permissions to
     */
    private fun setPrivateFilePermissions(path: Path) {
        try {
            Files.setPosixFilePermissions(
                path,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE
                )
            )
        } catch (_: UnsupportedOperationException) {
            // Windows doesn't support POSIX permissions, ignore
        } catch (_: SecurityException) {
            // Security manager denied access, ignore
        }
    }

    private class FileOutputStream(path: Path) : OutputStream() {
        private val delegate = Files.newOutputStream(path)

        override fun write(b: Int) = delegate.write(b)
        override fun write(b: ByteArray) = delegate.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
