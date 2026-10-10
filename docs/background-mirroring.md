# Background mirroring and automatic opening

An active mirror can continue while iMirror is backgrounded. The decoder uses a parking surface with rendering disabled until a valid activity surface returns. Switching output surfaces preserves reference frames; codecs that reject `setOutputSurface` fall back to rebuilding and may still wait for a keyframe. Decoder configuration travels with each queued frame so a bounded queue cannot lose the only SPS/PPS packet or apply a later format to older frames.

For automatic foregrounding, enable **Settings → Service → Open incoming connections automatically**. This is off by default. Android requires the **Display over other apps** permission for this background-launch path; iMirror does not draw an overlay. The settings row opens the system permission screen, after which the user returns and enables the option. If the firmware does not expose that permission screen, manual opening still works. Android/OEM background-start restrictions may differ; this path was exercised on Android TV 11, not established across all supported platforms.

The app opens on a new connection rather than repeatedly while connected, so pressing Home remains possible. Existing ongoing notifications remain available when permission is absent. The new option is read on connection and does not require restarting the receiver.

## Verification

- Connect from macOS Screen Mirroring while iMirror is in the background. With automatic opening enabled and permission granted, verify that the app comes forward and video/audio start.
- During an active mirror, press Home and reopen iMirror. Verify video resumes without disconnecting the sender.
- Repeat while switching resolution; test a fresh connection after disconnecting.
- Disable automatic opening and verify the app stays in the background. Test revoked/missing permission without crashing.
- Disconnect and check that codecs and parking surfaces are released.

Unit regression coverage includes output-surface switching without codec flushing, rejected/invalid surfaces, cleanup after stop failure, queue overflow preserving codec configuration, and ordering across format changes. Real hardware checks remain necessary for codec compatibility.
