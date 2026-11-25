package id.vern.antigravity.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreUpdateManagerTest {

    private val abcSha512 = "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
        "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"

    @Test
    fun sha512Hex_matchesKnownDigest() {
        assertEquals(abcSha512, CoreUpdateManager.sha512Hex("abc".toByteArray()))
    }

    @Test
    fun sha512Matches_acceptsNormalizedManifestValues() {
        assertTrue(CoreUpdateManager.sha512Matches(abcSha512, abcSha512))
        assertTrue(CoreUpdateManager.sha512Matches(abcSha512.uppercase(), abcSha512))
        assertTrue(CoreUpdateManager.sha512Matches("sha512:$abcSha512", abcSha512))
    }

    @Test
    fun sha512Matches_rejectsWrongOrTruncatedDigest() {
        assertFalse(CoreUpdateManager.sha512Matches("deadbeef", abcSha512))
        assertFalse(CoreUpdateManager.sha512Matches(abcSha512.dropLast(2), abcSha512))
        assertFalse(CoreUpdateManager.sha512Matches("", abcSha512))
    }

    @Test
    fun isVersionNewer_comparesNumericSegments() {
        assertTrue(CoreUpdateManager.isVersionNewer("1.5.4", "1.5.3"))
        assertTrue(CoreUpdateManager.isVersionNewer("1.6.0", "1.5.9"))
        assertFalse(CoreUpdateManager.isVersionNewer("1.5.3", "1.5.3"))
        assertFalse(CoreUpdateManager.isVersionNewer("1.5.2", "1.5.3"))
        assertTrue(CoreUpdateManager.isVersionNewer("1.5.10", "1.5.9 linux/arm64"))
        assertFalse(CoreUpdateManager.isVersionNewer("1.5.8", "1.5.9 linux/arm64"))
    }
}
