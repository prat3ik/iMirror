package dev.imirror.receiver.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.imirror.receiver.service.ProtocolState
import dev.imirror.receiver.util.Logger
import dev.imirror.receiver.util.NetworkUtils

/**
 * MdnsService — Advertises iMirror as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show iMirror in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know iMirror exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Living Room TV")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Living Room TV")
 */
class MdnsService(
    private val context: Context,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "iMirror", Android will register us as
     * "iMirror (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: iMirror (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {}
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // Count of how many services have confirmed registration.
    // Only when both reach 2 do we emit ProtocolState.ADVERTISING.
    @Volatile
    private var registeredCount = 0

    // Guard against double-start
    @Volatile
    private var isStarted = false

    // The name we requested to register — compared against the actual registered name
    // in onServiceRegistered to detect mDNS collision auto-renaming.
    @Volatile
    private var requestedName: String = ""

    // Name captured at start(), used when _airplay is chained after _raop registers.
    @Volatile
    private var pendingName: String = ""

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    fun start(displayNameOverride: String? = null) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        registeredCount = 0

        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting mDNS advertising as '$effectiveName'")
        requestedName = effectiveName
        pendingName = effectiveName

        // IMPORTANT: these two registrations MUST be serialised, not fired back-to-back.
        // Android's NsdManager cannot have two registrations in flight at once — the second
        // call silently cancels the first, with NEITHER onServiceRegistered nor
        // onRegistrationFailed ever being invoked for the loser. Observed on Android 14:
        // _raop registered fine while _airplay disappeared completely, which made the device
        // invisible in the iOS Screen Mirroring menu (that menu keys off _airplay._tcp).
        //
        // So: register _raop first, and only chain _airplay once its callback has landed.
        registerRaopService(effectiveName)
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     */
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        try {
            airPlayListener?.let { nsdManager.unregisterService(it) }
            raopListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            // Unregistration errors are non-fatal: service will expire via mDNS TTL
            Logger.e("Error unregistering mDNS services (non-fatal)", e)
        } finally {
            airPlayListener = null
            raopListener = null
            registeredCount = 0
            isStarted = false
            onStateChange(ProtocolState.DISABLED)
        }
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    fun restart(displayNameOverride: String? = null) {
        Logger.d("Restarting mDNS advertising")
        stop()
        start(displayNameOverride)
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Determines the effective name to advertise.
     * Uses [override] if non-blank; otherwise reads from the Android system.
     */
    private fun resolveDisplayName(override: String?): String {
        val trimmed = override?.trim() ?: ""
        return if (trimmed.isNotEmpty()) trimmed else NetworkUtils.getDeviceName(context)
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features iMirror supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun registerAirPlayService(displayName: String) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = displayName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records — shared with UnicastMdnsResponder so both
            // discovery paths advertise identical capabilities.
            airPlayTxtRecords(context).forEach { (key, value) -> setAttribute(key, value) }
        }

        airPlayListener = createRegistrationListener(
            serviceLabel = "_airplay._tcp",
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != requestedName) {
                    Logger.w("mDNS name collision detected: requested='$requestedName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                onActualNameRegistered(actualName)
            },
            onSuccess = { incrementAndCheckBothRegistered() },
            onFailure = { onStateChange(ProtocolState.ERROR) }
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, airPlayListener!!)
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Living Room TV"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String) {
        val macHex = NetworkUtils.getMacAddress().replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$macHex@$displayName"  // required RAOP format
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            // TXT records — shared with UnicastMdnsResponder (see airPlayTxtRecords).
            raopTxtRecords().forEach { (key, value) -> setAttribute(key, value) }
        }

        raopListener = createRegistrationListener(
            serviceLabel = "_raop._tcp",
            onRegisteredName = null,  // RAOP name has MAC prefix — not shown to users
            onSuccess = {
                incrementAndCheckBothRegistered()
                // Only now is it safe to register the second service — see start().
                registerAirPlayService(pendingName)
            },
            onFailure = { onStateChange(ProtocolState.ERROR) }
        )
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, raopListener!!)
    }

    /**
     * Emits [ProtocolState.ADVERTISING] only after both services have confirmed registration.
     * This prevents a brief "advertising" state where only one of the two required services
     * is live.
     */
    @Synchronized
    private fun incrementAndCheckBothRegistered() {
        registeredCount++
        if (registeredCount >= 2) {
            onStateChange(ProtocolState.ADVERTISING)
        }
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     * @param onSuccess        Called on [onServiceRegistered].
     * @param onFailure        Called on [onRegistrationFailed].
     */
    private fun createRegistrationListener(
        serviceLabel: String,
        onRegisteredName: ((String) -> Unit)?,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // NsdManager may append " (2)" to resolve name conflicts.
                // Log the actual name so we can debug picker-visibility issues.
                Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                onRegisteredName?.invoke(serviceInfo.serviceName)
                onSuccess()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Error codes from NsdManager:
                //   FAILURE_ALREADY_ACTIVE (3) — already registered; treat as success
                //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Logger.w("mDNS $serviceLabel already active — treating as success")
                    onSuccess()
                } else {
                    Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
                    isStarted = false
                    onFailure()
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceLabel")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire via mDNS TTL (~4500ms by default)
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        /**
         * AirPlay feature bitmask: advertise screen mirroring, video, and audio support.
         * See TECHNICAL_SPEC.md §8 for the full bit-level breakdown.
         */
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"

        /**
         * TXT records for the `_airplay._tcp` service.
         *
         * Single source of truth, used by BOTH discovery paths: the NsdManager
         * registration in this class and [UnicastMdnsResponder]. If the two ever
         * diverged, senders could see different capabilities depending on which
         * response reached them first.
         */
        internal fun airPlayTxtRecords(context: Context): Map<String, String> = linkedMapOf(
            "deviceid" to NetworkUtils.getMacAddress(),
            "features" to AIRPLAY_FEATURES,
            "model" to AIRPLAY_MODEL,
            "srcvers" to AIRPLAY_SERVER_VERSION,
            "vv" to "2",                                  // AirPlay protocol version 2
            "pi" to NetworkUtils.getPersistentUuid(context),
            "flags" to "0x4"                              // Screen-mirroring receiver
        )

        /** TXT records for the `_raop._tcp` (audio) service. See [airPlayTxtRecords]. */
        internal fun raopTxtRecords(): Map<String, String> = linkedMapOf(
            "cn" to "0,1,2,3",                            // Cipher numbers (encryption types)
            "da" to "true",                               // Digest authentication capable
            "et" to "0,3,5",                              // Encryption types supported
            "md" to "0,1,2",                              // Metadata types supported
            "sv" to "false",                              // Software volume control
            "tp" to "UDP",                                // Transport for audio RTP
            "vn" to "65537",                              // Version number (required)
            "vs" to AIRPLAY_SERVER_VERSION,
            "am" to AIRPLAY_MODEL
        )
    }
}
