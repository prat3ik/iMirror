package dev.imirror.receiver.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress

/**
 * UnicastMdnsResponderTest — Unit tests for the unicast mDNS compatibility responder.
 *
 * WHY: The responder exists for networks where multicast responses are dropped —
 * exactly the environment where a protocol bug would be invisible until a user's
 * Mac silently fails to discover the receiver. These tests pin the DNS wire
 * format (parsing incl. name compression, response layout, TTL rules) without
 * any sockets, via the internal [UnicastMdnsResponder.buildResponse] seam.
 *
 * Test naming convention: backtick sentences, same as MdnsServiceTest.
 */
class UnicastMdnsResponderTest {

    private lateinit var responder: UnicastMdnsResponder

    // Fixed fake IPv4 injected so tests never touch real interfaces.
    private val fakeAddress = InetAddress.getByName("192.0.2.7") as Inet4Address

    // A second address on the same device, as a WiFi-Direct interface would hold.
    private val secondaryAddress = InetAddress.getByName("192.0.2.99")

    @Before
    fun setup() {
        responder = UnicastMdnsResponder(
            displayName = "Test TV",
            macAddress = "aa:bb:cc:dd:ee:ff",
            airPlayPort = 7000,
            airPlayTxt = linkedMapOf("model" to "AppleTV5,3", "features" to "0x5A7FFFF7,0x1E"),
            raopTxt = linkedMapOf("tp" to "UDP"),
            localAddressProvider = { fakeAddress },
            localAddressesProvider = { setOf(fakeAddress, secondaryAddress) }
        )
    }

    // ─── Query building helpers ──────────────────────────────────────────────

    private fun writeName(out: ByteArrayOutputStream, name: String) {
        for (label in name.split('.')) {
            val bytes = label.toByteArray()
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    /** Builds a single-question mDNS query packet. */
    private fun query(name: String, qtype: Int, id: Int = 0, flags: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        writeU16(out, id)
        writeU16(out, flags)
        writeU16(out, 1)  // QDCOUNT
        writeU16(out, 0); writeU16(out, 0); writeU16(out, 0)
        writeName(out, name)
        writeU16(out, qtype)
        writeU16(out, 1)  // class IN
        return out.toByteArray()
    }

    private fun ancount(response: ByteArray) = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)
    private fun arcount(response: ByteArray) = ((response[10].toInt() and 0xFF) shl 8) or (response[11].toInt() and 0xFF)

    /** True if [needle] appears as a DNS label (length-prefixed) anywhere in the packet. */
    private fun containsLabel(packet: ByteArray, needle: String): Boolean {
        val bytes = byteArrayOf(needle.length.toByte()) + needle.toByteArray()
        outer@ for (i in 0..packet.size - bytes.size) {
            for (j in bytes.indices) {
                if (packet[i + j] != bytes[j]) continue@outer
            }
            return true
        }
        return false
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    /**
     * The core scenario: macOS's continuous browse sends a PTR query for
     * `_airplay._tcp.local`. The response must carry the instance PTR plus
     * SRV, TXT, and A as additionals so the Mac can resolve and connect from
     * this single unicast packet, without any further multicast round-trips.
     */
    @Test
    fun `airplay PTR query is answered with instance and full additionals`() {
        val response = responder.buildResponse(query("_airplay._tcp.local", 12), query("_airplay._tcp.local", 12).size, legacy = false)

        assertNotNull(response)
        response!!
        assertEquals(0x8400, ((response[2].toInt() and 0xFF) shl 8) or (response[3].toInt() and 0xFF))  // QR|AA
        assertEquals(1, ancount(response))
        assertEquals(3, arcount(response))            // SRV + TXT + A
        assertTrue(containsLabel(response, "Test TV"))
        assertTrue(containsLabel(response, "iMirror-AABBCCDDEEFF"))
        // SRV port 7000 = 0x1B58 must appear in the packet
        var foundPort = false
        for (i in 0 until response.size - 1) {
            if (response[i] == 0x1B.toByte() && response[i + 1] == 0x58.toByte()) foundPort = true
        }
        assertTrue(foundPort)
        // A record payload: the fake IPv4 bytes
        assertTrue(containsBytes(response, fakeAddress.address))
    }

    @Test
    fun `raop PTR query is answered with mac-prefixed instance name`() {
        val q = query("_raop._tcp.local", 12)
        val response = responder.buildResponse(q, q.size, legacy = false)

        assertNotNull(response)
        assertTrue(containsLabel(response!!, "AABBCCDDEEFF@Test TV"))
    }

    @Test
    fun `SRV query for the instance is answered case-insensitively`() {
        val q = query("test tv._AIRPLAY._tcp.LOCAL", 33)
        val response = responder.buildResponse(q, q.size, legacy = false)

        assertNotNull(response)
        assertEquals(1, ancount(response!!))
        assertTrue(containsLabel(response, "iMirror-AABBCCDDEEFF"))
    }

    @Test
    fun `queries about other services are ignored`() {
        val q = query("_googlecast._tcp.local", 12)
        assertNull(responder.buildResponse(q, q.size, legacy = false))
    }

    @Test
    fun `response packets are never answered`() {
        // QR bit set → this is someone else's response; answering would cause loops.
        val q = query("_airplay._tcp.local", 12, flags = 0x8400)
        assertNull(responder.buildResponse(q, q.size, legacy = false))
    }

    @Test
    fun `truncated and malformed packets are rejected without crashing`() {
        val good = query("_airplay._tcp.local", 12)
        assertNull(responder.buildResponse(good, 5, legacy = false))                  // shorter than header
        assertNull(responder.buildResponse(good, good.size - 3, legacy = false))     // question cut off
        // Forward-pointing compression pointer (invalid) must be rejected
        val evil = good.copyOf()
        evil[12] = 0xC0.toByte(); evil[13] = 0x20                                     // pointer to itself/forward
        assertNull(responder.buildResponse(evil, evil.size, legacy = false))
    }

    /**
     * macOS packs multiple questions per packet and compresses later names
     * against earlier ones. The parser must follow the pointer, and the
     * response must merge answers for both services.
     */
    @Test
    fun `compressed multi-question query is parsed and both services answered`() {
        val out = ByteArrayOutputStream()
        writeU16(out, 0); writeU16(out, 0)
        writeU16(out, 2)  // QDCOUNT = 2
        writeU16(out, 0); writeU16(out, 0); writeU16(out, 0)
        // Q1: _airplay._tcp.local PTR — "_tcp.local" starts at offset 12 + 1 + 8 = 21
        writeName(out, "_airplay._tcp.local")
        writeU16(out, 12); writeU16(out, 1)
        // Q2: _raop + pointer to "_tcp.local" at offset 21
        val raop = "_raop".toByteArray()
        out.write(raop.size); out.write(raop)
        out.write(0xC0); out.write(21)
        writeU16(out, 12); writeU16(out, 1)
        val packet = out.toByteArray()

        val response = responder.buildResponse(packet, packet.size, legacy = false)

        assertNotNull(response)
        assertEquals(2, ancount(response!!))
        assertTrue(containsLabel(response, "Test TV"))
        assertTrue(containsLabel(response, "AABBCCDDEEFF@Test TV"))
    }

    /**
     * Legacy queriers (source port ≠ 5353, e.g. `dig`) need the query ID echoed,
     * the question repeated, and TTLs capped at 10 s (RFC 6762 §6.7) so their
     * primitive caches never hold our records for long.
     */
    @Test
    fun `legacy query gets id echo repeated question and short TTLs`() {
        val q = query("_airplay._tcp.local", 12, id = 0xBEEF)
        val response = responder.buildResponse(q, q.size, legacy = true)

        assertNotNull(response)
        response!!
        assertEquals(0xBEEF, ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF))
        assertEquals(1, ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF))  // QDCOUNT echoed
        // No 32-bit TTL in the packet may exceed 10. TTLs sit 4 bytes after each
        // type/class pair; simplest robust check: the standard TTLs (4500=0x1194,
        // 120=0x78) must not appear as big-endian u32 anywhere.
        assertTrue(!containsBytes(response, byteArrayOf(0, 0, 0x11, 0x94.toByte())))
        assertTrue(!containsBytes(response, byteArrayOf(0, 0, 0, 0x78)))
    }

    @Test
    fun `updateInstanceName switches which names are answered`() {
        responder.updateInstanceName("Test TV (2)")

        val old = query("test tv._airplay._tcp.local", 33)
        assertNull(responder.buildResponse(old, old.size, legacy = false))

        val renamed = query("test tv (2)._airplay._tcp.local", 33)
        assertNotNull(responder.buildResponse(renamed, renamed.size, legacy = false))
    }

    @Test
    fun `A query for our host is answered with the current address`() {
        val q = query("iMirror-AABBCCDDEEFF.local", 1)
        val response = responder.buildResponse(q, q.size, legacy = false)

        assertNotNull(response)
        assertEquals(1, ancount(response!!))
        assertTrue(containsBytes(response, fakeAddress.address))
    }

    /**
     * Regression test for the self-conflict found on an LG CreateBoard (Android 13).
     *
     * The system daemon probes each name before registering and treats ANY answer
     * as a conflict. It probes on every active interface — including a WiFi-Direct
     * `p2p-wlan0-1` — so recognising only the primary address let the device answer
     * its own probe and register as "Test TV (2)" while the responder still
     * advertised "Test TV": one device, two entries in the AirPlay menu.
     */
    @Test
    fun `queries from any of our own addresses are ignored`() {
        assertTrue(responder.isOwnAddress(fakeAddress))
        assertTrue(responder.isOwnAddress(secondaryAddress))
        assertTrue(!responder.isOwnAddress(InetAddress.getByName("192.0.2.200")))
    }

    private fun containsBytes(packet: ByteArray, needle: ByteArray): Boolean {
        outer@ for (i in 0..packet.size - needle.size) {
            for (j in needle.indices) {
                if (packet[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
