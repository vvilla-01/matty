package ceo.villa.matty

import ceo.villa.matty.verify.MattyCertificates
import ceo.villa.matty.verify.sha256Colons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The two certificates the app pins are the ones published as Matty's. */
class MattyCertificatesTest {
    @Test
    fun theyAreThePublishedOnes() {
        assertEquals("E5:52:50:BE:A6:69:00:39:B7:84:9E:33:B0:49:08:8E:CB:B9:98:D9:F2:0A:AA:8C:7E:B2:DC:5A:43:63:27:3C", MattyCertificates.RSA_SHA256)
        assertEquals("75:78:CB:63:B4:3E:CC:6F:B1:27:64:51:54:F5:22:FB:15:D1:3A:26:08:E4:59:BC:F4:2A:7C:D1:41:86:A9:49", MattyCertificates.MLDSA_SHA256)
        assertEquals(MattyCertificates.RSA_SHA256, sha256Colons(MattyCertificates.rsaDer))
        assertEquals(MattyCertificates.MLDSA_SHA256, sha256Colons(MattyCertificates.mldsaDer))
    }

    @Test
    fun theReadmePublishesTheSame() {
        val readme = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "README.md") }
            .firstOrNull { it.isFile && it.readText().contains("AppVerifier") }
        assumeTrue("Matty's README isn't at hand", readme != null)
        val text = readme!!.readText()
        assertTrue(text.contains(MattyCertificates.RSA_SHA256))
        assertTrue(text.contains(MattyCertificates.MLDSA_SHA256))
    }
}
