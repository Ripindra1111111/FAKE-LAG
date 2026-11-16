# Fake Lag Tool (Android)

VpnService-based UDP drop tool. No root. Drops inbound UDP packets sized 50–250 bytes
to create fake lag. Builds APK via GitHub Actions.

## Quick start

1. Create a new GitHub repo (public or private).
2. Upload all files, preserving the folder structure.
3. Go to **Actions** → workflow **Build APK** runs automatically on push.
4. Wait ~3–5 minutes. Open the latest run → download artifact **FakeLagTool-debug-apk**.
5. Unzip → `app-debug.apk` → copy to phone → install (enable "Install from unknown sources").
6. Open app → tap **Cấp quyền VPN** → accept system dialog → tap the big button.

## How it works

- `VpnService.Builder` establishes a TUN interface with route `0.0.0.0/0`, DNS `1.1.1.1`, MTU 1500.
- Worker thread reads raw IPv4 packets from TUN.
- **UDP** with `length in [min, max]` → dropped (not re-injected). Counter + log broadcast.
- **UDP** outside range → forwarded via a `protect()`ed `DatagramSocket`, response wrapped back into a fresh IPv4+UDP packet and written to TUN.
- **TCP** → RST|ACK sent for fresh SYNs so browsers fail fast instead of hanging. Other TCP packets dropped.

## Important caveats

- **TCP is not forwarded.** A real TCP stack (tun2socks) is out of scope. While ON, web browsing breaks. To limit the damage, edit `FakeLagVpnService.startVpn()` and add:
  ```kotlin
  builder.addAllowedApplication("com.your.game.package")
