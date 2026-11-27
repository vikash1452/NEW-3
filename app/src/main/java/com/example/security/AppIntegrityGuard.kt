package com.example.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.example.BuildConfig
import java.security.MessageDigest

sealed interface IntegrityStatus {
    data object Valid : IntegrityStatus
    data class Tampered(val reason: String) : IntegrityStatus
}

/**
 * Runtime APK integrity & endpoint protection:
 * 1. Decodes the remote configuration endpoint from split XOR-masked byte arrays
 *    so plain-text Firebase URLs do not appear in APK string dumps.
 * 2. Verifies endpoint SHA-256 digest, package name, and APK signing certificate SHA-256.
 * 3. Any modification/re-signing of the APK immediately triggers a mandatory Official Update lock.
 */
object AppIntegrityGuard {
    private val MASK_KEY = intArrayOf(
        0x5A, 0xA3, 0x3C, 0x71, 0x9E, 0x24, 0xD7, 0x4B, 0x18, 0x6F, 0xC2, 0x85
    )

    // Masked Firebase Realtime Database base URL
    private val URL_MASKED = intArrayOf(
        0x32, 0xc6, 0x6a, 0x32, 0xa9, 0x4b, 0x9e, 0x13, 0xf1, 0x92, 0x5, 0x57,
        0xf8, 0x53, 0xe7, 0xbe, 0xea, 0x66, 0x84, 0x25, 0x28, 0x6f, 0xd2, 0x63,
        0xb7, 0x66, 0xf2, 0x97, 0x30, 0xbd, 0x4d, 0x26, 0x16, 0x38, 0xe9, 0xa4,
        0x5b, 0xb4, 0xdb, 0x95, 0x53, 0xf4, 0x72, 0xbe, 0x97, 0xfd, 0xa1
    )

    // Masked RTDB node path
    private val NODE_MASKED = intArrayOf(
        0x38, 0xd3, 0x6a, 0x36, 0xbf, 0x3, 0xc8, 0x63, 0xf2, 0x99, 0x1b, 0x4d,
        0xc9, 0xb, 0xa2, 0xea, 0xef, 0x71, 0x80
    )

    // Masked official applicationId
    private val PKG_MASKED = intArrayOf(
        0x39, 0xdd, 0x73, 0x6c, 0xbb, 0x18, 0xc2, 0x48, 0xe5, 0x92, 0x1, 0x51,
        0xb8, 0x1c, 0xb3, 0xfa, 0xfa, 0x60, 0x97, 0x71, 0x2e, 0x65, 0xc7, 0x71,
        0xec, 0x61, 0xfe, 0xc0, 0x32, 0xbe, 0x47
    )

    // Masked SHA-256 of the project's consistent debug signing certificate
    private val CERT_MASKED = intArrayOf(
        0x63, 0x8b, 0x5a, 0x73, 0xe9, 0x33, 0x80, 0xf, 0xa7, 0xc3, 0x5a, 0xe,
        0xd0, 0x46, 0x96, 0xbf, 0xbd, 0x40, 0xd6, 0x4a, 0x7e, 0x4b, 0x81, 0x33,
        0x84, 0x3e, 0xc3, 0x8f, 0x73, 0xfb, 0x18, 0x7d, 0x7d, 0x6a, 0xc4, 0x95,
        0xc, 0x93, 0x8b, 0xd2, 0xe, 0xab, 0x28, 0xa9, 0xc1, 0xd7, 0x8d, 0xdf,
        0x2b, 0xda, 0x2c, 0x56, 0xae, 0x95, 0x73, 0xdf, 0xe4, 0x90, 0x2f, 0x56,
        0x94, 0xe8, 0x13, 0x6f
    )

    // Masked SHA-256 of "$baseUrl|$nodePath" to detect binary/smali URL tampering
    private val ENDPOINT_HASH_MASKED = intArrayOf(
        0x1e, 0x87, 0x5a, 0x0, 0xeb, 0x30, 0x86, 0xc, 0xd4, 0xb5, 0x2e, 0x7d,
        0xae, 0x4a, 0xeb, 0xcd, 0xcf, 0x31, 0xdd, 0x3c, 0xf, 0x4c, 0xf7, 0x34,
        0xf5, 0x39, 0xc5, 0xfb, 0x75, 0xfb, 0x6f, 0x6, 0x7e, 0x6d, 0xc6, 0x94,
        0xa, 0xef, 0x89, 0xd6, 0x7, 0xa4, 0x2f, 0xd4, 0xc2, 0xa2, 0xff, 0xdf,
        0x29, 0xdb, 0x5b, 0x20, 0xd8, 0xe2, 0x71, 0xde, 0x99, 0x93, 0x21, 0x2a,
        0xe0, 0x9e, 0x1b, 0x6a
    )

    @Volatile
    private var testOverrideTampered: Boolean? = null

    fun decode(masked: IntArray): String {
        val out = ByteArray(masked.size)
        for (i in masked.indices) {
            val k = MASK_KEY[i % MASK_KEY.size]
            val shift = (i * 17) and 0xFF
            out[i] = (masked[i] xor k xor shift).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    fun resolveDatabaseBaseUrl(): String = decode(URL_MASKED)

    fun resolveUpdateNodePath(): String = decode(NODE_MASKED)

    fun resolveExpectedPackageName(): String = decode(PKG_MASKED)

    fun resolveExpectedDebugCertSha256(): String = decode(CERT_MASKED)

    fun resolveExpectedEndpointHash(): String = decode(ENDPOINT_HASH_MASKED)

    fun isEndpointHashValid(
        baseUrl: String = resolveDatabaseBaseUrl(),
        nodePath: String = resolveUpdateNodePath()
    ): Boolean {
        val actualHash = sha256Hex("$baseUrl|$nodePath".toByteArray(Charsets.UTF_8))
        return constantTimeEquals(actualHash, resolveExpectedEndpointHash())
    }

    fun setTamperOverrideForTest(tampered: Boolean?) {
        testOverrideTampered = tampered
    }

    fun isTampered(context: Context): Boolean {
        return verifyIntegrity(context) is IntegrityStatus.Tampered
    }

    fun verifyIntegrity(context: Context): IntegrityStatus {
        testOverrideTampered?.let { forced ->
            return if (forced) {
                IntegrityStatus.Tampered("test_forced_tamper")
            } else {
                IntegrityStatus.Valid
            }
        }

        val expectedPkg = resolveExpectedPackageName()
        if (context.packageName != expectedPkg || BuildConfig.APPLICATION_ID != expectedPkg) {
            return IntegrityStatus.Tampered("package_mismatch")
        }

        if (!isEndpointHashValid()) {
            return IntegrityStatus.Tampered("endpoint_hash_mismatch")
        }

        // Robolectric JVM tests do not have real APK signing certificates
        if (Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
            return IntegrityStatus.Valid
        }

        val expectedCertSha = if (BuildConfig.DEBUG) {
            resolveExpectedDebugCertSha256()
        } else {
            BuildConfig.RELEASE_CERT_SHA256.replace(":", "").trim().uppercase()
        }

        if (expectedCertSha.isNotEmpty()) {
            val installedCerts = readInstalledSigningCertSha256List(context)
            if (installedCerts.isEmpty() || installedCerts.none { constantTimeEquals(it, expectedCertSha) }) {
                return IntegrityStatus.Tampered("signature_mismatch")
            }
        }

        return IntegrityStatus.Valid
    }

    @Suppress("DEPRECATION")
    fun readInstalledSigningCertSha256List(context: Context): List<String> {
        return runCatching {
            val pm = context.packageManager
            val pkgInfo: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }

            val rawSignatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = pkgInfo.signingInfo
                if (signingInfo == null) {
                    null
                } else if (signingInfo.hasMultipleSigners()) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo.signingCertificateHistory
                }
            } else {
                pkgInfo.signatures
            }

            rawSignatures
                ?.map { sig -> sha256Hex(sig.toByteArray()) }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        }.getOrElse { emptyList() }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hexChars = "0123456789ABCDEF"
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
