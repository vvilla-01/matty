package ceo.villa.matty.verify

import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Matty's two signing certificates, the public halves of the keys every official release of the
 * app is signed with (APK Signature Scheme v3.2): RSA 4096, the classical one, and ML-DSA-87, the
 * post-quantum one. Taken from the released APK, never from a key. The same two sign the Matty
 * plugin's releases, which is how the app knows a plugin release is the owner's.
 *
 * Their SHA-256 are the ones the README publishes under "Verify"; MattyCertificatesTest holds
 * them to it.
 */
object MattyCertificates {
    /** SHA-256 of the RSA 4096 certificate's DER, as the README writes it. */
    const val RSA_SHA256 = "E5:52:50:BE:A6:69:00:39:B7:84:9E:33:B0:49:08:8E:CB:B9:98:D9:F2:0A:AA:8C:7E:B2:DC:5A:43:63:27:3C"

    /** SHA-256 of the ML-DSA-87 certificate's DER, as the README writes it. */
    const val MLDSA_SHA256 = "75:78:CB:63:B4:3E:CC:6F:B1:27:64:51:54:F5:22:FB:15:D1:3A:26:08:E4:59:BC:F4:2A:7C:D1:41:86:A9:49"

    /** The RSA certificate, DER in base64 (the body of its PEM). */
    internal const val RSA_BASE64 =
        "MIIE5jCCAs6gAwIBAgIJANLH4P2idBwEMA0GCSqGSIb3DQEBCwUAMCAxDjAMBgNVBAoTBVZpbGxhMQ4wDAYDVQQDEwVNYXR0eTAg" +
        "Fw0yNjExMTcxNzIyMDJaGA8yMTI2MTAyNDE3MjIwMlowIDEOMAwGA1UEChMFVmlsbGExDjAMBgNVBAMTBU1hdHR5MIICIjANBgkq" +
        "hkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA4xX4vFNUHBXa4CoOjin+DCQrYFNna2Htj2WWFL5tS1BGo85mARaSBhjx+ar0UVJcMS6E" +
        "vW7LmJAXQvmr4b8/y49VRa9nEM1CRQUJ7Z6RespdO18L8y4X7JpAQkdwKoPBdjA+At8DzHqo+eh31Q4XY12fCJr05WkcaSr0rhjU" +
        "MDZ5CtQLY1OMxOlgXXyd4h1sFtiT4ex4hSv5yf/zabPmkJp/MhszhTteXL0OG+dJ2vc1/j61ku/OROYfo1FgAdVkD2GFCigPdQTN" +
        "QJuxKCWqIZubkXRmswcbC68vSrmKpOegyQ1UFe7LhPvLHbRB72sKDU311w2rKWyE4ShxlH+WDEeQBGsw1InY6E+NqdXl4M5F5a40" +
        "khyHpbC++QZHKhO/Xkv9wcp6G2yRNJlIhxwTbxLgOIdQkvPgjpFTqS5TYGJpnJ5SRtlmqRDoOtjRnLPXfbiKq8VGXB8Ut+R3L1eD" +
        "TXBXwjKJXPtSNvEuLEVpKfDJH1zr4ZgeQYwKqebmbIAPTuar5o4Lpvw4wme3FKGDwzP1SPzSjT6/Q6KDf+SyOKghl/TzWc1vC4G+" +
        "ZGK7z+Sc4c9k/fSBW2hTOnG5ZSpxIMKevi3l0KqQZeuZo2pRAvSqUATQOZWzklYjCOMbK6pkQbavGzTcLKLebhPJmhIzQwoRcLh7" +
        "C5AqNR54/qdNNqcCAwEAAaMhMB8wHQYDVR0OBBYEFPHVepwPOm6IwzvynGuNoV5Tmt/+MA0GCSqGSIb3DQEBCwUAA4ICAQDYS1Ri" +
        "IUrB0YiRy3J/c1ITSXughGXhTwwEIE9XdXNpxjblgPypPt2t1OpLnXn53z2jegCZXWrB3mxRwSov+nGkdqD0U88piWdMn/wh5/q4" +
        "nsZR0fOFf6mzfBSMDlkcuVUNLr/BjfhUQHD8zs1jNwjvulC8NX079f30rGR2CIHar/kd8Y8LEhTsOXP3LuwQQK7YD45CAkwDzSuw" +
        "NN1lI+n4p0Tbmsdfn/dSeLDqIquPaJyaF2/1GqBwEDNte2KRldAj1Qq7+Zlw7+SaSTFXs8oVERBxS6VFy5UrRl7KNPdxtDKAKBKO" +
        "joiTPJb+os/lldL2W+QW0sakNdIgDbm5vPLdG9jBLxENlfn+aKxTD1nxi8IzCEctu3t6dejh6/2Kl+LItIwHlTXgjYqJxUnECD7V" +
        "9MqPbB24lF5ePrU/qQiJdj52n5qh6bK7naDC+fCuTLUBjP7pJuPuSdwlgBLf10Qi+wUe++qvDZL8WY6Mu3r74ZmkUQq5b9B5eN/0" +
        "cq6sIPF0xhH9dm+VsCFetOTDc0ywKjcNqJGtdiirWiYLKFqYSUm2iV+Ct/CdKsvHy6GeTA19bK0w6tg5no/Tr2FER7ZugE0Rx8QE" +
        "ZHEsNYkpdRrG++4etkAjlkmAjfcaqttMJVhHaJCP2v9ygaPKPlhukvgzz+kOP7+izLAYgWuaZSceng=="

    /** The ML-DSA-87 certificate, DER in base64 (the body of its PEM). */
    internal const val MLDSA_BASE64 =
        "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" +
        "DhYWGyQsMw=="

    val rsaDer: ByteArray get() = Base64.getDecoder().decode(RSA_BASE64)
    val mldsaDer: ByteArray get() = Base64.getDecoder().decode(MLDSA_BASE64)

    /** A certificate from its DER, by the platform's own X.509 reader. */
    fun certificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
}

/** SHA-256 of [bytes] as upper-case hex pairs joined by colons, the way the README and apksigner write it. */
fun sha256Colons(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(":") { "%02X".format(it) }
