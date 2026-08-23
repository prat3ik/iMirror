package dev.imirror.receiver.airplay

import dev.imirror.receiver.util.Logger
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * UnicastMdnsResponder — Answers AirPlay discovery queries with UNICAST responses.
 *
 * WHY: Some home routers (observed on Airtel/Broadcom-based units with IGMP snooping
 * or "Wireless Multicast Forwarding") silently drop multicast packets SENT by a
 * wireless client, while still delivering multicast TO it. On such networks the
 * system mDNS daemon's multicast answers never reach a browsing Mac:
 *
 * - iOS works anyway: opening Screen Mirroring starts a FRESH browse whose first
 *   query sets the QU ("unicast response requested") bit, and the daemon's unicast
 *   reply gets through.
 * - macOS fails: Control Centre keeps a PERMANENT background browse running for
 *   `_airplay._tcp`, so its ongoing queries are standard QM (multicast-response)
 *   queries. The daemon answers those via multicast — which the router drops —
 *   and the TV never appears in the Mac's Screen Mirroring menu.
 *
 * HOW: This responder joins the mDNS group (224.0.0.251:5353), parses incoming
 * queries itself, and answers every query about our services via UNICAST straight
 * back to the querier — regardless of the QU/QM bit. RFC 6762 §5.4 requires
 * clients to accept such unicast responses, and macOS does. Unicast is not
 * subject to the router's multicast forwarding problem, so discovery works.
 *
 * This runs ALONGSIDE the system registration done by [MdnsService] — the system
 * daemon still handles announcements, goodbyes, and name-conflict resolution.
 * On healthy networks senders simply receive (and dedupe) both answers.
 *
 * The DNS wire-format logic is socket-free and exposed via [buildResponse] so
 * unit tests can exercise it without touching the network.
 *
 * Example:
 *   val responder = UnicastMdnsResponder(
 *       displayName = "Living Room TV",
 *       macAddress = NetworkUtils.getMacAddress(),
 *       airPlayPort = MdnsService.AIRPLAY_PORT,
 *       airPlayTxt = MdnsService.airPlayTxtRecords(context),
 *       raopTxt = MdnsService.raopTxtRecords(),
 *   )
 *   responder.start()
 *   responder.stop()
 */
class UnicastMdnsResponder(
    displayName: String,
    macAddress: String,
    private val airPlayPort: Int,
    private val airPlayTxt: Map<String, String>,
    private val raopTxt: Map<String, String>,
    /** Injectable for tests. Returns the device's current IPv4 address, or null if offline. */
    private val localAddressProvider: () -> Inet4Address? = { defaultLocalAddress() },
    /**
     * Injectable for tests. Returns EVERY address this device currently holds —
     * see [isOwnAddress] for why one address is not enough.
     */
    private val localAddressesProvider: () -> Set<InetAddress> = { allLocalAddresses() }
) {

    // MAC with colons stripped — used in the RAOP instance name and our hostname label.
    private val macHex = macAddress.replace(":", "").uppercase()

    // DNS names are modelled as label lists (NOT dot-joined strings): an mDNS instance
    // label may legally contain spaces and dots, so "Pratik AirPlay._airplay._tcp.local"
    // is the FOUR labels ["Pratik AirPlay", "_airplay", "_tcp", "local"].
    @Volatile private var airPlayInstance: List<String> = instanceLabels(displayName, SERVICE_AIRPLAY)
    @Volatile private var raopInstance: List<String> = instanceLabels("$macHex@$displayName", SERVICE_RAOP)

    // SRV target host. We advertise our own hostname (with its A record in the same
    // packet) instead of guessing the system daemon's hostname — senders cache both
    // records together, so resolution never depends on multicast.
    private val hostLabels: List<String> = listOf("iMirror-$macHex", "local")

    @Volatile private var running = false
    @Volatile private var socket: MulticastSocket? = null
    private var thread: Thread? = null

    // Every address this device holds, refreshed periodically — see [isOwnAddress].
    @Volatile private var cachedLocalAddresses: Set<InetAddress> = emptySet()
    @Volatile private var localAddressesStamp = 0L

    /**
     * Starts the responder thread. Idempotent.
     *
     * Socket errors are non-fatal by design: on failure the app degrades to plain
     * system-daemon discovery (the pre-1.0.1 behaviour) instead of crashing.
     */
    fun start() {
        if (running) return
        running = true
        thread = Thread({ runLoop() }, "iMirror-UnicastMdns").apply {
            isDaemon = true
            start()
        }
        Logger.i("Unicast mDNS responder started (instance='${airPlayInstance.first()}')")
    }

    /** Stops the responder and closes its socket. Safe to call if never started. */
    fun stop() {
        running = false
        socket?.close()
        socket = null
        thread = null
        Logger.i("Unicast mDNS responder stopped")
    }

    /**
     * Updates the advertised instance name after an mDNS name collision.
     *
     * [MdnsService] reports the ACTUAL registered name (e.g. "iMirror (2)") via
     * its collision callback — we must answer for the same name the system daemon
     * registered, or senders would see two diverging instances.
     */
    fun updateInstanceName(newName: String) {
        airPlayInstance = instanceLabels(newName, SERVICE_AIRPLAY)
        raopInstance = instanceLabels("$macHex@$newName", SERVICE_RAOP)
    }

    // ─── Receive loop ────────────────────────────────────────────────────────

    private fun runLoop() {
        try {
            val sock = MulticastSocket(MDNS_PORT)
            socket = sock
            val group = InetAddress.getByName(MDNS_GROUP)
            val wifiInterface = pickMulticastInterface()
            if (wifiInterface != null) {
                sock.joinGroup(InetSocketAddress(group, MDNS_PORT), wifiInterface)
            } else {
                @Suppress("DEPRECATION")
                sock.joinGroup(group)
            }

            val buf = ByteArray(RECEIVE_BUFFER_SIZE)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                sock.receive(packet)
                val src = packet.address ?: continue
                if (isOwnAddress(src)) continue

                // Source port 5353 = fully-compliant mDNS querier; anything else is a
                // "legacy" one-shot query (RFC 6762 §6.7) needing ID echo and short TTLs.
                val legacy = packet.port != MDNS_PORT
                val response = buildResponse(packet.data, packet.length, legacy) ?: continue
                sock.send(DatagramPacket(response, response.size, src, packet.port))
                Logger.d("Unicast mDNS answer sent to ${src.hostAddress}:${packet.port}")
            }
        } catch (e: Exception) {
            // Socket.close() during stop() lands here too — only log real failures.
            if (running) Logger.e("Unicast mDNS responder terminated", e)
        }
    }

    /**
     * True when [addr] belongs to THIS device, on any interface.
     *
     * WHY this must cover every interface, not just the primary one: before
     * registering, the system mDNS daemon PROBES each name ("does anyone already
     * own this?") and treats any answer as a conflict, renaming the service to
     * "<name> (2)". Since we advertise the same names as the daemon, answering
     * its own probe makes the device collide with itself.
     *
     * Devices with a second active interface make this concrete: an LG CreateBoard
     * (Android 13) runs `wlan0` alongside a WiFi-Direct `p2p-wlan0-1`, and the
     * daemon probes on BOTH. Matching only the primary address let the p2p-sourced
     * probes through, so iMirror answered them and the receiver registered as
     * "Pratik AirPlay (2)" while our responder still advertised the original name —
     * two entries for one device in the sender's AirPlay menu.
     *
     * The address set is cached briefly because interfaces come and go at runtime
     * (a p2p interface appears the moment WiFi Direct starts).
     */
    internal fun isOwnAddress(addr: InetAddress): Boolean {
        val now = System.currentTimeMillis()
        if (now - localAddressesStamp > LOCAL_ADDRESS_CACHE_MS || cachedLocalAddresses.isEmpty()) {
            cachedLocalAddresses = localAddressesProvider()
            localAddressesStamp = now
        }
        return addr in cachedLocalAddresses
    }

    /**
     * Chooses the network interface to join the multicast group on.
     * Prefers a wlan interface with an IPv4 address; falls back to any usable one.
     */
    private fun pickMulticastInterface(): NetworkInterface? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
                nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                    nif.inetAddresses.toList().any { it is Inet4Address }
            }
        } catch (e: Exception) {
            Logger.e("Failed to enumerate network interfaces", e)
            return null
        }
        return candidates.firstOrNull { it.name.startsWith("wlan") } ?: candidates.firstOrNull()
    }

    // ─── DNS wire format: query parsing + response building ──────────────────

    /**
     * Parses one mDNS query packet and builds the unicast response for it.
     *
     * Returns null when no response is warranted: the packet is not a plain query
     * (QR bit set / non-zero opcode), is malformed, or asks about names that are
     * not ours.
     *
     * @param data   Raw packet bytes (may be larger than the datagram).
     * @param length Actual datagram length.
     * @param legacy True when the query came from a port other than 5353 — the
     *   response then echoes the query ID, repeats the question, and caps TTLs
     *   at 10 s as RFC 6762 §6.7 requires.
     */
    internal fun buildResponse(data: ByteArray, length: Int, legacy: Boolean): ByteArray? {
        if (length < DNS_HEADER_SIZE) return null
        val id = readU16(data, 0)
        val flags = readU16(data, 2)
        if (flags and FLAG_QR != 0) return null            // a response, not a query
        if ((flags shr 11) and 0xF != 0) return null       // non-standard opcode
        val qdCount = readU16(data, 4)
        if (qdCount == 0) return null

        val questions = mutableListOf<Question>()
        var offset = DNS_HEADER_SIZE
        repeat(qdCount) {
            val (labels, nextOffset) = readName(data, length, offset) ?: return null
            if (nextOffset + 4 > length) return null
            val qtype = readU16(data, nextOffset)
            val qclass = readU16(data, nextOffset + 2) and 0x7FFF  // strip QU bit
            offset = nextOffset + 4
            if (qclass == CLASS_IN || qclass == TYPE_ANY) {
                questions.add(Question(labels, qtype))
            }
        }

        val answers = mutableListOf<Record>()
        val additionals = mutableListOf<Record>()
        val address = localAddressProvider()

        for (q in questions) {
            when {
                namesEqual(q.labels, SERVICE_AIRPLAY) && q.type.isPtrOrAny() -> {
                    answers.add(ptrRecord(SERVICE_AIRPLAY, airPlayInstance))
                    additionals.add(srvRecord(airPlayInstance))
                    additionals.add(txtRecord(airPlayInstance, airPlayTxt))
                    address?.let { additionals.add(aRecord(it)) }
                }
                namesEqual(q.labels, SERVICE_RAOP) && q.type.isPtrOrAny() -> {
                    answers.add(ptrRecord(SERVICE_RAOP, raopInstance))
                    additionals.add(srvRecord(raopInstance))
                    additionals.add(txtRecord(raopInstance, raopTxt))
                    address?.let { additionals.add(aRecord(it)) }
                }
                namesEqual(q.labels, airPlayInstance) -> {
                    if (q.type.isSrvOrAny()) answers.add(srvRecord(airPlayInstance))
                    if (q.type.isTxtOrAny()) answers.add(txtRecord(airPlayInstance, airPlayTxt))
                    if (q.type.isSrvOrAny()) address?.let { additionals.add(aRecord(it)) }
                }
                namesEqual(q.labels, raopInstance) -> {
                    if (q.type.isSrvOrAny()) answers.add(srvRecord(raopInstance))
                    if (q.type.isTxtOrAny()) answers.add(txtRecord(raopInstance, raopTxt))
                    if (q.type.isSrvOrAny()) address?.let { additionals.add(aRecord(it)) }
                }
                namesEqual(q.labels, hostLabels) && (q.type == TYPE_A || q.type == TYPE_ANY) -> {
                    address?.let { answers.add(aRecord(it)) }
                }
            }
        }
        if (answers.isEmpty()) return null

        // Answers may double as additionals when several questions overlap — drop duplicates.
        val extra = additionals.distinctBy { it.key }.filter { add -> answers.none { it.key == add.key } }

        val out = ByteArrayOutputStream()
        writeU16(out, if (legacy) id else 0)
        writeU16(out, FLAG_QR or FLAG_AA)
        writeU16(out, if (legacy) questions.size else 0)   // legacy responses repeat the question
        writeU16(out, answers.size)
        writeU16(out, 0)                                   // no authority records
        writeU16(out, extra.size)
        if (legacy) {
            for (q in questions) {
                writeName(out, q.labels)
                writeU16(out, q.type)
                writeU16(out, CLASS_IN)
            }
        }
        for (record in answers) writeRecord(out, record, legacy)
        for (record in extra) writeRecord(out, record, legacy)
        return out.toByteArray()
    }

    // ─── Record construction ─────────────────────────────────────────────────

    private data class Question(val labels: List<String>, val type: Int)

    /**
     * One resource record ready for serialisation.
     * [key] identifies a record uniquely (for deduplication across questions).
     */
    private class Record(val name: List<String>, val type: Int, val ttl: Int, val rdata: ByteArray) {
        val key: String = "${name.joinToString(".").lowercase()}/$type"
    }

    private fun ptrRecord(service: List<String>, instance: List<String>): Record {
        val rdata = ByteArrayOutputStream().also { writeName(it, instance) }.toByteArray()
        return Record(service, TYPE_PTR, TTL_LONG, rdata)
    }

    private fun srvRecord(instance: List<String>): Record {
        val rdata = ByteArrayOutputStream().also {
            writeU16(it, 0)             // priority
            writeU16(it, 0)             // weight
            writeU16(it, airPlayPort)
            writeName(it, hostLabels)
        }.toByteArray()
        return Record(instance, TYPE_SRV, TTL_SHORT, rdata)
    }

    private fun txtRecord(instance: List<String>, txt: Map<String, String>): Record {
        val rdata = ByteArrayOutputStream().also { out ->
            for ((k, v) in txt) {
                val entry = "$k=$v".toByteArray(Charsets.UTF_8)
                out.write(entry.size)
                out.write(entry)
            }
        }.toByteArray()
        return Record(instance, TYPE_TXT, TTL_LONG, rdata)
    }

    private fun aRecord(address: Inet4Address): Record =
        Record(hostLabels, TYPE_A, TTL_SHORT, address.address)

    private fun writeRecord(out: ByteArrayOutputStream, record: Record, legacy: Boolean) {
        writeName(out, record.name)
        writeU16(out, record.type)
        writeU16(out, CLASS_IN)        // no cache-flush bit on unicast responses (RFC 6762 §10.2)
        writeU32(out, if (legacy) minOf(record.ttl, TTL_LEGACY) else record.ttl)
        writeU16(out, record.rdata.size)
        out.write(record.rdata)
    }

    // ─── Wire-format primitives ──────────────────────────────────────────────

    /**
     * Reads a DNS name starting at [start], following compression pointers
     * (macOS packs many questions per packet and compresses aggressively).
     *
     * @return the labels and the offset just past the name IN THE ORIGINAL stream
     *   (i.e. past the pointer, not past the pointed-to name), or null if malformed.
     */
    private fun readName(data: ByteArray, length: Int, start: Int): Pair<List<String>, Int>? {
        val labels = mutableListOf<String>()
        var offset = start
        var resumeAt = -1     // where parsing continues after the first pointer jump
        var hops = 0
        while (true) {
            if (offset >= length) return null
            val len = data[offset].toInt() and 0xFF
            when {
                len == 0 -> {
                    val end = if (resumeAt >= 0) resumeAt else offset + 1
                    return labels to end
                }
                len and 0xC0 == 0xC0 -> {                  // compression pointer
                    if (offset + 1 >= length) return null
                    if (++hops > MAX_POINTER_HOPS) return null
                    val target = ((len and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    if (resumeAt < 0) resumeAt = offset + 2
                    if (target >= offset) return null      // pointers must point backwards
                    offset = target
                }
                len and 0xC0 != 0 -> return null           // reserved label types
                else -> {
                    if (offset + 1 + len > length) return null
                    labels.add(String(data, offset + 1, len, Charsets.UTF_8))
                    if (labels.size > MAX_LABELS) return null
                    offset += 1 + len
                }
            }
        }
    }

    /** Writes a name uncompressed — always legal, and simpler than tracking offsets. */
    private fun writeName(out: ByteArrayOutputStream, labels: List<String>) {
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
    }

    // DNS names compare case-insensitively (RFC 6762 §16), label by label.
    private fun namesEqual(a: List<String>, b: List<String>): Boolean =
        a.size == b.size && a.indices.all { a[it].equals(b[it], ignoreCase = true) }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun Int.isPtrOrAny() = this == TYPE_PTR || this == TYPE_ANY
    private fun Int.isSrvOrAny() = this == TYPE_SRV || this == TYPE_ANY
    private fun Int.isTxtOrAny() = this == TYPE_TXT || this == TYPE_ANY

    companion object {
        private const val MDNS_GROUP = "224.0.0.251"
        private const val MDNS_PORT = 5353
        private const val RECEIVE_BUFFER_SIZE = 9000
        private const val DNS_HEADER_SIZE = 12

        private const val FLAG_QR = 0x8000
        private const val FLAG_AA = 0x0400

        private const val CLASS_IN = 1
        internal const val TYPE_A = 1
        internal const val TYPE_PTR = 12
        internal const val TYPE_TXT = 16
        internal const val TYPE_SRV = 33
        internal const val TYPE_ANY = 255

        // Standard mDNS TTLs: 75 min for shared records, 120 s for host-specific ones.
        private const val TTL_LONG = 4500
        private const val TTL_SHORT = 120
        // Legacy (non-5353) queriers get short TTLs — RFC 6762 §6.7.
        private const val TTL_LEGACY = 10

        private const val MAX_POINTER_HOPS = 10
        private const val MAX_LABELS = 32

        /** How long the local-address set is trusted before re-enumeration. */
        private const val LOCAL_ADDRESS_CACHE_MS = 10_000L

        private val SERVICE_AIRPLAY = listOf("_airplay", "_tcp", "local")
        private val SERVICE_RAOP = listOf("_raop", "_tcp", "local")

        private fun instanceLabels(instance: String, service: List<String>): List<String> =
            listOf(instance) + service

        /** Every address held by this device, across all interfaces. */
        private fun allLocalAddresses(): Set<InetAddress> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }

        /** Current IPv4 of the first usable interface, preferring wlan. */
        private fun defaultLocalAddress(): Inet4Address? = try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { it.name.startsWith("wlan") }
            interfaces.firstNotNullOfOrNull { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress }
            }
        } catch (e: Exception) {
            null
        }
    }
}
