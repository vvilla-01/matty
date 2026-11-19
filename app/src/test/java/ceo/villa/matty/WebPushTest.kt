package ceo.villa.matty

import ceo.villa.matty.PushTestKit.b64
import ceo.villa.matty.PushTestKit.encrypt
import ceo.villa.matty.PushTestKit.pad
import ceo.villa.matty.push.P256
import ceo.villa.matty.push.WebPush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Opening a Web Push message (RFC 8291), held to the RFC's own example and to what must be refused. */
class WebPushTest {
    // RFC 8291 section 5 and appendix A, whitespace as the RFC prints it.
    private val uaPrivate = b64("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94")
    private val uaPublic = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcx aOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4")
    private val asPublic = b64("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIg Dll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8")
    private val auth = b64("BTBZMqHH6r4Tts7J_aSIgg")
    private val salt = b64("DGv6ra1nlYgDCS1FRnbzlw")
    private val header = b64("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z 9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml mlMoZIIgDll6e3vCYLocInmYWAmS6Tlz AC8wEqKK6PBru3jl7A8")
    private val ciphertext = b64("8pfeW0KbunFT06SuDKoJH9Ql87S1QUrd irN6GcG7sFz1y1sqLgVi1VhjVkHsUoEs bI_0LpXMuGvnzQ")
    private val body = b64(
        """
        DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml
        mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT
        pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN
        """,
    )
    private val keys = WebPush.Keys(P256.privateKey(uaPrivate), uaPublic, auth)

    @Test fun theRfcsExampleOpens() {
        // The example's body is its header and its ciphertext, as appendix A says.
        assertArrayEquals(header + ciphertext, body)
        assertEquals(86, header.size)
        assertEquals("When I grow up, I want to be a watermelon", WebPush.decrypt(body, keys)?.toString(Charsets.UTF_8))
    }

    @Test fun everyIntermediateValueIsTheRfcs() {
        val d = WebPush.derive(keys, salt, asPublic)!!
        assertArrayEquals(b64("kyrL1jIIOHEzg3sM2ZWRHDRB62YACZhhSlknJ672kSs"), d.ecdh)
        assertArrayEquals(b64("Snr3JMxaHVDXHWJn5wdC52WjpCtd2EIEGBykDcZW32k"), d.prkKey)
        assertArrayEquals(b64("S4lYMb_L0FxCeq0WhDx813KgSYqU26kOyzWUdsXYyrg"), d.ikm)
        assertArrayEquals(b64("09_eUZGrsvxChDCGRCdkLiDXrReGOEVeSCdCcPBSJSc"), d.prk)
        assertArrayEquals(b64("oIhVW04MRdy2XN9CiKLxTg"), d.cek)
        assertArrayEquals(b64("4h_95klXJ5E_qnoN"), d.nonce)
    }

    @Test fun keysRoundTripAsTheServerIsGivenThem() {
        assertArrayEquals(uaPublic, P256.encode(P256.decodePublic(uaPublic)!!))
        assertArrayEquals(uaPrivate, P256.scalar(P256.privateKey(uaPrivate)))
        val fresh = WebPush.newKeys()
        assertEquals(87, fresh.p256dh.length)
        assertEquals(22, fresh.authText.length)
        assertNotNull(P256.decodePublic(fresh.public))
    }

    @Test fun whatThePluginSendsOpens() {
        val fresh = WebPush.newKeys()
        val text = "x".repeat(2000).toByteArray()
        val sent = encrypt(fresh.public, fresh.auth, pad(text))
        assertEquals(86 + 2048 + 16, sent.size)
        assertArrayEquals(text, WebPush.decrypt(sent, fresh))
        // Only the delimiter, nothing before it: an empty message.
        assertArrayEquals(ByteArray(0), WebPush.decrypt(encrypt(fresh.public, fresh.auth, byteArrayOf(2)), fresh))
    }

    @Test fun anythingElseIsRefused() {
        val fresh = WebPush.newKeys()
        val plain = pad("hello".toByteArray())
        fun refused(body: ByteArray, why: String) = assertNull(why, WebPush.decrypt(body, fresh))

        refused(encrypt(fresh.public, ByteArray(16), plain), "another authentication secret")
        refused(encrypt(WebPush.newKeys().public, fresh.auth, plain), "for another key")
        refused(encrypt(fresh.public, fresh.auth, plain).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }, "a broken tag")
        refused(encrypt(fresh.public, fresh.auth, plain).also { it[100] = (it[100].toInt() xor 1).toByte() }, "a changed ciphertext")
        refused(encrypt(fresh.public, fresh.auth, "hello".toByteArray() + byteArrayOf(1, 0, 0)), "a record that is not the last (delimiter 0x01)")
        refused(encrypt(fresh.public, fresh.auth, "hello".toByteArray()), "no delimiter at all")
        refused(encrypt(fresh.public, fresh.auth, ByteArray(40)), "padding only")
        refused(encrypt(fresh.public, fresh.auth, plain, rs = 1000), "a record size smaller than the record")
        refused(encrypt(fresh.public, fresh.auth, plain, rs = 8192), "a record size over 4096")
        refused(encrypt(fresh.public, fresh.auth, plain, rs = 17), "a record size under RFC 8188's 18")
        val sender = PushTestKit.senderPair()
        val point = PushTestKit.point(sender.public as java.security.interfaces.ECPublicKey)
        refused(encrypt(fresh.public, fresh.auth, plain, sender = sender, idlen = 64), "a key id that is not 65 bytes")
        val offCurve = point.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        refused(encrypt(fresh.public, fresh.auth, plain, sender = sender, senderPoint = offCurve), "a sender key off the curve")
        // The largest body a distributor hands over opens; one byte more is refused for its size alone.
        val largest = encrypt(fresh.public, fresh.auth, ByteArray(3993) + byteArrayOf(2))
        assertEquals(4096, largest.size)
        assertArrayEquals(ByteArray(3993), WebPush.decrypt(largest, fresh))
        refused(encrypt(fresh.public, fresh.auth, ByteArray(3994) + byteArrayOf(2)), "over 4096 bytes")
        refused(ByteArray(102), "shorter than a header, a tag and a delimiter")
        refused(ByteArray(0), "nothing")
    }

    @Test fun pointsOffTheCurveAreNotKeys() {
        assertNull(P256.decodePublic(uaPublic.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }))
        assertNull(P256.decodePublic(uaPublic.copyOf(64)))
        assertNull(P256.decodePublic(uaPublic.copyOf().also { it[0] = 2 }))
        assertNull(P256.decodePublic(ByteArray(65).also { it[0] = 4 }))
    }
}
