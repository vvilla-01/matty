package ceo.villa.matty

import ceo.villa.matty.ui.settings.LICENCES_ASSET
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LicencesTest {
    private val text = listOf(File("src/main/assets"), File("app/src/main/assets"))
        .map { File(it, LICENCES_ASSET) }.first { it.isFile }.readText()

    @Test
    fun theNoticesTravelWithTheApp() {
        // What a BSD licence asks of a binary: the copyright line, the conditions and the disclaimer.
        assertTrue("Copyright (c) 2011, The WebRTC project authors" in text)
        assertTrue("Redistributions in binary form must reproduce" in text)
        assertTrue("THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS" in text)
        listOf("libvpx", "libaom", "dav1d", "Opus", "libyuv", "libsrtp", "BoringSSL", "Abseil", "Mattermost", "Apache")
            .forEach { assertTrue(it, it in text) }
    }
}
