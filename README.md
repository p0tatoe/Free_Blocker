# FreeBlocker

FreeBlocker is an open-source, local VPN-based DNS sinkhole for Android. It acts as a device-wide ad and tracker blocker by intercepting DNS queries locally and blocking those that match known tracking or advertising domains, without sending your traffic to an external filtering server.

## How it Works

FreeBlocker uses Android's `VpnService` API to establish a local TUN interface. It redirects all device DNS traffic (IPv4 and IPv6) into this tunnel. 

1. **Traffic Interception:** The `TunPacketRouter` reads raw IP packets from the TUN interface.
2. **DNS Extraction:** `IpPacketParser` parses these IP/UDP packets to extract raw DNS queries.
3. **Filtering:** Queries are evaluated by `DnsFilter` against a compiled list of blocked domains.
4. **Resolution or Blocking:**
   - **Blocked:** If the domain matches a blocklist, FreeBlocker immediately responds with an `NXDOMAIN` (non-existent domain) error, preventing the ad/tracker from loading.
   - **Allowed:** If the domain is safe, `DnsProxyServer` forwards the query to an encrypted upstream DNS resolver using DNS-over-QUIC (DoQ) with a fallback to DNS-over-HTTPS (DoH). 

## Architecture & Key Components

The app follows a modern Android architecture using Jetpack Compose for the UI, Kotlin Coroutines/Flows for asynchronous programming, and DataStore for persistence.

### Core (`dev.michaelylee.freeblocker.core`)
The heavy lifting of the VPN and packet processing happens here.
- `MyVpnService.kt`: The `VpnService` implementation. It manages the TUN interface, handles start/stop intents, and orchestrates the VPN lifecycle.
- `TunPacketRouter.kt`: Asynchronously reads/writes raw IP packets to/from the TUN file descriptor.
- `IpPacketParser.kt`: A zero-allocation (where possible) parser for IPv4/IPv6 and UDP headers to extract DNS payloads.
- `DnsProxyServer.kt`: Handles upstream DNS forwarding. Uses Cronet for high-performance DoQ (HTTP/3) and OkHttp for DoH fallback.
- `DnsFilter.kt`: Fast, thread-safe domain matching using a Radix tree / Trie structure for the loaded blocklists.

### Data (`dev.michaelylee.freeblocker.data`)
Manages blocklists and user preferences.
- `BlocklistRepository.kt`: Manages the downloading, parsing, and compilation of blocklist files from various sources.
- `BlocklistFetcher.kt`: Fetches blocklist raw text from URLs.
- `UserPreferences.kt`: DataStore wrapper for user settings, whitelisted apps, custom upstream configs, and manual domain rules.

### UI (`dev.michaelylee.freeblocker.ui`)
Built entirely in Jetpack Compose with Material 3.
- `MainActivity.kt`: The single-activity entry point hosting the Compose navigation.
- `VpnViewModel.kt`: The main ViewModel bridging the UI and the core services. Exposes state via `StateFlow`.
- `BlockedWebsitesScreen.kt`: Shows the home tab with the master toggle, upstream configuration, and recently blocked/paused domains.
- `BlocklistsScreen.kt`: Allows users to manage built-in and custom blocklist source URLs.
- `AppsScreen.kt`: App bypass management, where users can exclude specific installed apps from the VPN tunnel.

## Building and Running

### Prerequisites
- Android Studio Koala (or newer)
- Minimum SDK: API 34 (Android 14)
- Target SDK: API 37

### Building
Open the project in Android Studio and sync Gradle. You can build the project by clicking the **Run** button or executing the following command in the terminal:

```bash
./gradlew assembleDebug
```

### Required Permissions
- `android.permission.BIND_VPN_SERVICE`: System-level permission to run a VPN.
- `android.permission.INTERNET` & `ACCESS_NETWORK_STATE`: For fetching blocklists and forwarding allowed DNS queries upstream.
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_SPECIAL_USE`: Required to keep the VPN running in the background.
- `android.permission.POST_NOTIFICATIONS`: To display the persistent VPN connection notification.
- `android.permission.QUERY_ALL_PACKAGES`: To list installed apps for the VPN bypass (whitelist) feature.

## Contributing

When contributing to FreeBlocker, please keep the following in mind:
- **Performance:** DNS resolution happens for almost every network request on the device. Hot paths in `IpPacketParser` and `DnsFilter` must be extremely efficient and avoid unnecessary memory allocations.
- **Privacy:** FreeBlocker is designed to be a *local* sinkhole. It should never log queries to disk or send analytics containing user browsing data. Allowed queries must only be sent over encrypted transports (DoQ/DoH).
- **Happy Eyeballs:** Upstream connections use a Happy Eyeballs-style race to ensure the fastest possible DNS resolution. Ensure new upstream features don't introduce blocking delays.
