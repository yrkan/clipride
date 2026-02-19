# ClipRide

[![License](https://img.shields.io/badge/License-MIT-0d1117?style=flat-square&logo=opensourceinitiative&logoColor=white)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Karoo%202%2F3-0d1117?style=flat-square&logo=android&logoColor=white)](https://www.hammerhead.io/)
[![Downloads](https://img.shields.io/github/downloads/yrkan/clipride/total?style=flat-square&color=0d1117&logo=github&logoColor=white)](https://github.com/yrkan/clipride/releases)
[![Release](https://img.shields.io/github/v/release/yrkan/clipride?style=flat-square&color=0d1117&logo=github&logoColor=white)](https://github.com/yrkan/clipride/releases/latest)
[![Website](https://img.shields.io/badge/Web-clipride.com-0d1117?style=flat-square&logo=google-chrome&logoColor=00E676)](https://clipride.com)

**Control your GoPro camera from your Hammerhead Karoo bike computer.**

ClipRide is an open-source [Karoo Extension](https://developers.hammerhead.io/) that connects to GoPro cameras over Bluetooth Low Energy. Start and stop recording, add highlight markers, monitor battery level and SD card capacity — all from the device already on your handlebars.

No phone needed. No WiFi needed. Just Bluetooth.

## Features

### Camera Control

- **Start/Stop Recording** — one tap on the data field or via the Bonus Action button in the ride screen side menu
- **Add Highlight Markers** — tag key moments during your ride for GoPro Quik auto-editing. Works with manual tap or automatic triggers (see Auto-Highlights below)
- **Camera Power** — put the camera to sleep or wake it up directly from a data field
- **Battery Monitoring** — real-time battery percentage with color-coded thresholds: green (30%+), yellow (15-29%), red (<15%)
- **SD Card Status** — remaining recording time displayed in hours and minutes
- **Recording Timer** — live duration counter that ticks every second during recording, synchronized with the camera's internal clock every 5 seconds

### Auto-Record

Automatically control recording based on your ride state:

- **Ride Start** — begins recording when you start a ride (skips if camera is already recording)
- **Ride Pause** — stops recording when you pause (manual pause). Any pending delayed start is also cancelled
- **Auto-Pause** — optionally keeps recording during auto-pause events like traffic lights (configurable)
- **Ride Resume** — resumes recording when you continue after a manual pause (if the "Continue on Auto-Pause" option is enabled)
- **Ride End** — stops recording when you finish the ride
- **Start Delay** — configurable delay before recording begins (0 / 5 / 10 / 15 seconds), allowing time to get situated before the camera starts

### Auto-Highlights

Automatically add GoPro highlight markers during your ride based on performance events. Each auto-highlight has a 5-second cooldown to prevent spam. Available triggers:

- **Lap Highlight** — adds a highlight every time you press the lap button on Karoo
- **Peak Power Highlight** — adds a highlight when your power output exceeds a configurable threshold (default: 500W)
- **Max Speed Highlight** — adds a highlight when your speed exceeds a configurable threshold (default: 50 km/h)

Each trigger can be individually enabled or disabled and configured with its own threshold.

### Battery & SD Card Alerts

In-ride alerts displayed directly on the Karoo screen during recording:

- **Low Battery Alert** — shown once per ride when the camera battery drops below the configured low threshold (default: 20%). Displayed as a yellow banner for 3 seconds.
- **Critical Battery Alert** — shown once per ride when the battery drops below the configured critical threshold (default: 10%). Displayed as a red banner for 5 seconds. Takes priority over the low battery alert if both thresholds are crossed simultaneously.
- **SD Card Low Alert** — shown once per ride when the remaining recording time on the SD card drops below 5 minutes. Displayed as a red banner for 5 seconds with the remaining time in minutes.

All alerts reset at the beginning of each new ride. Alerts are only triggered while the ride is actively recording (not during pause or idle states).

### Data Fields

Four data fields available for your Karoo ride screens:

| Data Field | Type ID | Description | Tap Action | Sizes |
|---|---|---|---|---|
| **Camera Status** | `gopro-status` | Combined view: recording state indicator, duration timer, battery percentage, and SD card remaining time | Toggle recording | Full / Half / Quarter (adapts layout to available space) |
| **Battery** | `gopro-battery` | Camera battery percentage with color-coded display | None | Any |
| **Recording** | `gopro-recording` | Recording timer when active, "REC" prompt when idle, with hint text ("tap to start" / "tap to stop") | Toggle recording | Any |
| **Power** | `gopro-power` | Camera connection/power state: ON (green) / SCAN / WAIT / PAIR / OFF (gray) | Toggle power (sleep/wake) | Any |

All data fields display "---" when the camera is not connected. Fields update in real-time:
- Battery updates every 5 seconds (from status poll or push notification)
- Recording timer ticks every second with a local counter, re-synchronized with the camera's actual duration every 5 seconds
- Connection state changes are reflected immediately

#### Camera Status Tiers

The Camera Status data field adapts its layout based on the grid cell size:

- **Full** (large cell): Status dot + "REC"/"IDLE" label + duration timer on one line, battery percentage on second line, "SD: Xh Ym" on third line
- **Half** (medium cell): Status dot + duration timer on one line, battery + SD on second line
- **Quarter** (small cell): Status dot + battery percentage only

### Connection Management

- **Auto-Connect** — automatically connects to the saved camera when the extension starts
- **Persistent Reconnection** — if the camera disconnects (power cycle, out of range, sleep), ClipRide enters a reconnect loop:
  - **Rapid phase** (first 10 attempts): retries every 3 seconds with active BLE scanning
  - **Slow phase** (attempt 11+): retries every 15 seconds indefinitely, allowing the camera to be off for hours and reconnect when powered back on
- **Stale Bond Recovery** — GoPro cameras may clear their BLE bond keys after a power cycle. If ClipRide detects that the connection setup fails while the Android side still shows the device as bonded, it automatically removes the stale bond and re-initiates pairing on the next attempt
- **Keep-Alive** — sends a BLE setting write every 60 seconds to prevent the camera from entering sleep mode during a ride
- **Status Polling** — queries 7 camera status IDs every 5 seconds (encoding state, battery, video duration, SD remaining, preset group, busy, ready) in addition to push notifications from BLE subscriptions

### Bonus Actions

Three Bonus Action buttons accessible from the Karoo ride screen side menu:

- **Record** — toggle recording on/off
- **Highlight** — add a GoPro highlight marker at the current moment
- **Settings** — open the ClipRide settings screen

## Supported Cameras

All cameras that implement the [Open GoPro](https://gopro.github.io/OpenGoPro/) BLE v2.0 specification are fully supported. ClipRide uses standard OpenGoPro commands (shutter, highlight, battery, SD status, presets) that are consistent across all supported models.

| Camera | Model ID | Support Level | Notes |
|---|---|---|---|
| GoPro MAX 2 | 64 (H24.02) | Full | 360-degree camera. All ClipRide features work identically to HERO models. |
| GoPro HERO13 Black | 65 (H24.01) | Full | Known issue: BLE may not function after camera wakes from sleep. Power cycle the camera if it becomes unresponsive. |
| GoPro HERO12 Black | 62 (H23.01) | Full | |
| GoPro HERO11 Black | 58 (H22.01) | Full | |
| GoPro HERO11 Black Mini | 60 (H22.03) | Full | |
| GoPro HERO10 Black | 57 (H21.01) | Full | |
| GoPro HERO9 Black | 55 (HD9.01) | Full | Requires firmware v01.70.00 or later for full OpenGoPro support. |

HERO8 and older cameras do not implement OpenGoPro and are not compatible. The original GoPro MAX (2019) is also not supported — only the MAX 2 (2025) implements the OpenGoPro BLE specification. Cameras from other manufacturers (Insta360, DJI Action, Sony, etc.) use proprietary protocols and are not supported.

When ClipRide connects to an unrecognized camera model, it displays a warning alert but attempts to communicate using the standard OpenGoPro BLE protocol. Basic commands may work on future GoPro models before explicit support is added.

## Supported Devices

- **Hammerhead Karoo 3** — primary target, fully tested and supported
- **Hammerhead Karoo 2** — should work via sideload (untested, minSdk 26 is compatible)

## Installation

### From APK (Sideload)

1. Download the latest APK from [Releases](https://github.com/yrkan/clipride/releases)
2. Transfer to your Karoo via the Hammerhead Companion App, or install directly:
   ```bash
   adb install clipride.apk
   ```
3. Open ClipRide from the Karoo app drawer
4. Pair your camera (see [First-Time Setup](#first-time-setup))
5. Add ClipRide data fields to your ride screens

### From Source

Prerequisites:
- Android Studio (Arctic Fox or newer) with JDK 17
- Android SDK with API level 35

```bash
git clone https://github.com/yrkan/clipride.git
cd clipride

# Build the karoo-ext dependency first (required — not available on public Maven)
git clone https://github.com/hammerheadnav/karoo-ext.git
cd karoo-ext
./gradlew publishToMavenLocal
cd ..

# Build ClipRide
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/clipride.apk`.

For a release build with ProGuard minification:
```bash
./gradlew assembleRelease
```

Install on a connected Karoo:
```bash
adb install app/build/outputs/apk/debug/clipride.apk
```

## First-Time Setup

### Pairing Your Camera

1. **Turn on your GoPro camera** and make sure it is not in sleep mode
2. **Open ClipRide** on your Karoo (from the app drawer or extension settings)
3. **Tap "Connect Camera"** — ClipRide will scan for nearby GoPro cameras advertising the BLE service UUID (`FEA6`)
4. **Select your camera** from the list (displayed as "GoPro XXXX" where XXXX is the camera's BLE identifier)
5. **Grant Notification Access** if prompted:
   - Karoo 3 has no notification shade, so Android BLE pairing requests appear as invisible notifications
   - ClipRide uses a `NotificationListenerService` to detect and auto-confirm these pairing notifications
   - You need to enable this once: tap "Open Notification Access" → toggle ClipRide on → return to the app
   - On Karoo, this can also be enabled via ADB: `adb shell cmd notification allow_listener com.clipride/com.clipride.ble.PairingNotificationListener`
6. **Wait for pairing to complete** — ClipRide reads the WAP_PASSWORD characteristic to trigger BLE SMP pairing, then sends the post-connect setup sequence (SetPairingComplete, SetCameraControl, disable WiFi AP, register status subscriptions)
7. **Done** — your camera address is saved. ClipRide will auto-connect on every future extension start

### Adding Data Fields to Ride Screens

1. Go to your ride profile on Karoo
2. Edit a ride page layout
3. Tap "Add Field" → scroll to the **ClipRide** section
4. Choose from four fields: **Camera Status**, **Battery**, **Recording**, or **Power**
5. During a ride, tap the Recording, Status, or Power fields to interact with the camera

### Recommended Data Field Setup

For most riders, a single **Camera Status** field in a full or half-size cell provides all the information you need: recording state, duration, battery, and SD card capacity. Add a **Power** field if you want to put the camera to sleep and wake it from the Karoo.

If you prefer dedicated fields, use **Recording** (for the timer and tap-to-record) and **Battery** (for a large, easy-to-read percentage).

## Configuration

All settings are accessible from the ClipRide settings screen on Karoo. Tap the **Settings** Bonus Action during a ride, or open ClipRide from the app drawer.

### Auto-Record Settings

| Setting | Options | Default | Description |
|---|---|---|---|
| Auto-Record | On / Off | On | Start and stop recording automatically with the ride lifecycle |
| Start Delay | No delay / 5s / 10s / 15s | No delay | Wait before starting recording after ride begins. The pending start is cancelled if the ride is paused before the delay expires. |
| Continue on Auto-Pause | On / Off | On | Keep recording during auto-pause events (e.g., stopped at a traffic light). When off, recording stops on any pause. |

### Auto-Highlight Settings

| Setting | Options | Default | Description |
|---|---|---|---|
| Lap Highlight | On / Off | Off | Add a GoPro highlight marker on every lap button press |
| Power Highlight | On / Off | Off | Add a highlight when instantaneous power exceeds the threshold |
| Power Threshold | 300W - 1000W | 500W | Watt threshold for power highlights |
| Speed Highlight | On / Off | Off | Add a highlight when speed exceeds the threshold |
| Speed Threshold | 30 - 80 km/h | 50 km/h | Speed threshold for speed highlights |

### Alert Settings

| Setting | Options | Default | Description |
|---|---|---|---|
| Low Battery Threshold | 15% / 20% / 25% / 30% | 20% | Battery level that triggers a low battery in-ride alert |
| Critical Battery Threshold | 5% / 10% / 15% | 10% | Battery level that triggers a critical battery in-ride alert |

SD card low alert triggers at <5 minutes remaining recording time (not configurable).

### Camera Management

- **Connect Camera** — scan for and pair a new GoPro camera
- **Forget Camera** — remove the saved camera. Settings are preserved, only the pairing is removed.
- **Reset All Settings** — restore all settings to defaults while keeping the paired camera

## How It Works

ClipRide communicates with GoPro cameras using the [Open GoPro BLE specification](https://gopro.github.io/OpenGoPro/). Here is the full connection lifecycle:

### 1. Discovery

ClipRide scans for BLE peripherals advertising the GoPro service UUID (`0000FEA6-0000-1000-8000-00805f9b34fb`). Scan results are filtered to connectable devices only and deduplicated by peripheral address. The scan runs for up to 30 seconds.

### 2. Connection

Once a GoPro is found, ClipRide connects using the Nordic BLE Library's `CentralManager.connect()` with configurable retry (5 attempts, 3-second delay, 30-second timeout per attempt). After the BLE link is established, ClipRide requests the highest supported MTU for efficient packet transfer.

### 3. Service Discovery & Characteristic Resolution

ClipRide waits for the GATT service list to populate, then resolves the following characteristics from the GoPro BLE service (`b5f90001-aa8d-11e3-9046-0002a5d5c51b`):

| Characteristic | UUID (short) | Purpose |
|---|---|---|
| CQ_COMMAND | `0072` | Write commands (shutter, sleep, preset change) |
| CQ_SETTING | `0074` | Write settings (keep-alive) |
| CQ_QUERY | `0076` | Write queries (status poll, register subscriptions) |
| CQ_COMMAND_RSP | `0073` | Receive command responses (notifications) |
| CQ_SETTING_RSP | `0075` | Receive setting responses (notifications) |
| CQ_QUERY_RSP | `0077` | Receive query/status responses (notifications) |
| CN_NETWORK_MGMT | Network management | Write SetPairingComplete protobuf |
| WAP_PASSWORD | `0003` | Read to trigger SMP pairing |

### 4. BLE Bonding

If the device is not already bonded:
1. ClipRide reads the WAP_PASSWORD characteristic, which triggers the Android SMP pairing flow
2. On Karoo 3 (which has no notification shade), the pairing request appears as a system notification. The `PairingNotificationListener` (a `NotificationListenerService`) detects this notification and automatically triggers the "Pair & connect" action.
3. If implicit pairing through the WAP_PASSWORD read doesn't result in a bond, ClipRide falls back to an explicit `createBond(TRANSPORT_LE)` call

If the device is already bonded, this step is skipped entirely.

### 5. Notification Subscription

ClipRide subscribes to the three response characteristics (CQ_COMMAND_RSP, CQ_SETTING_RSP, CQ_QUERY_RSP). Incoming notifications are assembled from multi-packet BLE fragments using Nordic's `mergeIndexed()` with the GoPro TLV continuation protocol, then parsed into typed response objects.

### 6. Post-Connect Commands

Three commands are sent in sequence after notification subscriptions are active:

1. **SetPairingComplete** — a Protobuf message on the Network Management characteristic containing the device name "ClipRide"
2. **SetCameraControl(EXTERNAL)** — a Protobuf command (`FeatureId=0xF1, ActionId=0x69`) that tells the camera an external device is in control
3. **AP_OFF** — disables the camera's WiFi access point (`0x17 0x01 0x00`) to save battery and avoid interference with Karoo connectivity

### 7. Status Registration

ClipRide registers for push notifications on 7 status IDs via a CQ_QUERY register command (`0x52`):

| Status ID | Name | Size | Description |
|---|---|---|---|
| 10 | ENCODING | 1 byte | Whether the camera is currently recording (0=idle, 1=encoding) |
| 70 | BATTERY_PERCENTAGE | 1 byte | Internal battery level (0-100) |
| 35 | REMAINING_VIDEO | 4 bytes | Remaining recording time on SD card in **seconds** |
| 13 | VIDEO_DURATION | 4 bytes | Current recording duration in seconds |
| 96 | PRESET_GROUP | 4 bytes | Currently active preset group ID |
| 8 | BUSY | 1 byte | Whether the camera is processing (0=idle, 1=busy) |
| 82 | READY | 1 byte | Whether the camera is ready for commands |

The register response includes the initial values for all status IDs, which populate the StateFlows before the connection state is set to CONNECTED. This ensures data fields display correct values immediately upon connection.

### 8. Steady State

Once connected, two background tasks run continuously:

- **Keep-Alive** (every 60 seconds): writes `0x5B 0x01 0x42` to CQ_SETTING (a LED setting update that the camera treats as a heartbeat). If 3 consecutive keep-alive writes fail, the task stops and waits for the natural BLE disconnect to trigger reconnection.
- **Status Poll** (every 5 seconds): queries all 7 status IDs via CQ_QUERY (`0x13` command) to ensure values stay synchronized even if push notifications are missed.

The camera also sends asynchronous push notifications via CQ_QUERY_RSP when status values change (e.g., recording starts/stops, battery level changes).

### 9. Recording Duration Display

The recording timer uses a dual-flow architecture for smooth display:

- `recordingDuration` — raw value from the camera, updated on poll/push (every ~5 seconds)
- `displayDuration` — local ticker that increments every second during recording

When recording starts, the ticker begins at 0 and increments each second. Every 5 seconds, the poll brings the camera's actual duration, and `displayDuration` is re-synchronized to this value. This provides a smooth 1-second ticker without the jitter that would result from relying solely on 5-second polls.

When a shutter command succeeds, an optimistic update immediately sets `isRecording` to the new state. A 3-second debounce window prevents conflicting BLE push notifications from briefly reverting the UI.

### 10. Reconnection

When the BLE connection drops (detected via Nordic's `peripheral.state` flow emitting `Disconnected`):

1. All background tasks are cancelled (keep-alive, status polling, notification collectors, duration ticker)
2. Status flows are reset (isRecording=false, duration=0, etc.)
3. Connection state is set to DISCONNECTED so data fields immediately show "---"
4. The reconnect loop begins with rapid-phase retries (3-second intervals, up to 10 attempts)
5. After 10 failed rapid attempts, transitions to slow-phase retries (15-second intervals, unlimited)
6. On each attempt: scan for the device → connect → run full setup → resume steady state

If a connection attempt fails while the Android side still considers the device bonded (stale bond from camera power cycle), ClipRide removes the bond before the next attempt to trigger fresh pairing.

## Architecture

```
UI Layer (Jetpack Compose)
├── SettingsActivity        Settings screen with camera management and preferences
├── PairingActivity         Scan, select, and pair a GoPro camera
└── Theme / Components      Design system (pure black OLED background, ClipRide color palette)

Karoo Layer
├── ClipRideExtension       KarooExtension service (entry point, lifecycle, BonusActions)
├── GoProDevice             Bridges BLE StateFlows → Karoo DeviceEvent system
├── ClipRideActionReceiver  BroadcastReceiver for tap actions on data fields
├── ClipRidePreferences     SharedPreferences wrapper for all settings
├── DataTypes               4 Glance-based data fields (Battery, Recording, Status, Power)
│   └── glance/             Shared Glance components (DataFieldContainer, ValueText, StatusDot, colors)
└── Handlers                3 ride event handlers (AutoRecord, BatteryAlert, HighlightEvent)

BLE Layer
├── GoProBleManager         Connection lifecycle, reconnect loop, keep-alive, status polling, StateFlows
├── GoProBleProtocol        TLV fragmentation/reassembly using Nordic mergeIndexed + MergeResult
├── GoProCommands           High-level command API (shutter, highlight, preset, sleep, hardware info)
├── GoProStatus             BLE status ID and setting ID constants
├── GoProUuid               BLE service and characteristic UUID constants
├── CameraCompatibility     Model detection and known issues registry
├── ErrorMessages           BLE exception → user-friendly message mapping
├── PairingNotificationListener  NotificationListenerService for auto-confirming BLE pairing on Karoo 3
└── BluetoothPermissions    Runtime permission check helpers

Utilities
├── KarooExtensions         consumerFlow<T>() and streamDataFlow() — callback-to-Flow wrappers for karoo-ext
└── FeedbackHelper          Haptic feedback for tap interactions
```

### Key Libraries

| Library | Version | Purpose |
|---|---|---|
| [Nordic BLE Library](https://github.com/NordicSemiconductor/Kotlin-BLE-Library) | 2.0.0-alpha02 | BLE scanning, connection, GATT operations, packet assembly |
| [karoo-ext](https://developers.hammerhead.io/) | 1.1.8 | Karoo extension SDK (KarooExtension, DataTypes, DeviceEvents, RideState, InRideAlert) |
| [Jetpack Glance](https://developer.android.com/jetpack/compose/glance) | 1.1.1 | RemoteViews-based data field rendering for Karoo ride screens |
| [Hilt](https://dagger.dev/hilt/) | 2.52 | Dependency injection (singletons for BleManager, Commands, Preferences, KarooSystem) |
| [Protobuf Lite](https://protobuf.dev/) | 4.28.2 | GoPro protobuf message encoding (SetCameraControl) |
| [Timber](https://github.com/JakeWharton/timber) | 5.0.1 | Logging with "ClipRide" tag |
| Kotlin Coroutines | 1.8.0 | Async BLE operations, StateFlow-based reactive state |
| Jetpack Compose | BOM 2024.06.00 | UI for Settings and Pairing activities |

### Build Configuration

- **Application ID**: `com.clipride`
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 35 (Android 15)
- **Compile SDK**: 35
- **JVM Target**: 17
- **Kotlin**: 2.0.0 with Compose compiler plugin
- **ProGuard**: enabled for release builds with resource shrinking

### Dependency Note

The `karoo-ext` library (v1.1.8) is not published on a public Maven repository. It is resolved from `mavenLocal()`. Before building ClipRide, you must clone and publish karoo-ext locally — see [From Source](#from-source) instructions above.

## Known Limitations

- **Camera settings cannot be changed while recording** — this is a GoPro firmware limitation. Resolution, FPS, and FOV must be set before starting a recording.
- **WiFi features are not supported** — file download, live preview, and media browsing require WiFi, which conflicts with Karoo's own connectivity. ClipRide is BLE-only by design.
- **Single camera only** — only one GoPro can be connected at a time. The saved device address is stored in SharedPreferences.
- **HERO8, original MAX (2019), and older cameras are not compatible** — these cameras do not implement the Open GoPro BLE specification.
- **GoPro HERO13 BLE wake bug** — some HERO13 units have a firmware issue where BLE does not function after the camera wakes from sleep. Power cycling the camera resolves it. ClipRide's reconnect loop will automatically connect once BLE becomes responsive.
- **Camera name is immutable over BLE** — the GoPro BLE advertising name ("GoPro XXXX") does not change even if you rename the camera in the GoPro app.
- **Keep-alive does not force reconnect** — if keep-alive writes fail (e.g., the camera became unresponsive but the BLE link hasn't dropped yet), ClipRide stops the keep-alive task and waits for the natural BLE disconnect rather than forcing a reconnection cycle.

## Troubleshooting

### Camera not found during scan

- Make sure the camera is **turned on** and not in sleep mode
- Verify that **Bluetooth is enabled** on the Karoo (Settings → Bluetooth)
- Move the camera **closer to the Karoo** — BLE range is approximately 10 meters in open air, significantly less through pockets or bags
- Check that the camera is not already connected to another device (phone, another Karoo). GoPro allows only one BLE connection at a time.
- If the camera was previously paired with a different device, it may need to be manually unpaired from that device first

### "Setup Required" during pairing

- ClipRide needs **Notification Access** permission to auto-confirm the BLE pairing notification
- Tap "Open Notification Access" in the ClipRide dialog
- Toggle **ClipRide** on in the Notification Access list
- Return to ClipRide and tap **Connect**
- Alternatively, enable via ADB: `adb shell cmd notification allow_listener com.clipride/com.clipride.ble.PairingNotificationListener`

### Camera disconnects after every power cycle

- This is **expected behavior**. GoPro cameras may clear their BLE bond keys when powered off and on.
- ClipRide automatically detects the stale bond condition (setup fails while Android reports the device as BONDED), removes the old bond, and re-pairs on the next connection attempt.
- The reconnect loop runs indefinitely — just turn the camera back on and wait a few seconds.

### Data field shows "---"

- The camera is not currently connected
- Check the **Power** data field or ClipRide settings for the connection state
- If the state shows "Disconnected", the reconnect loop is in slow phase (15-second intervals). The camera may be off or out of range.
- If the state shows "Scanning" or "Connecting", a reconnection attempt is in progress

### Recording timer appears stuck or jumps

- The timer ticks locally every second but re-synchronizes with the camera's actual duration every 5 seconds
- If the local ticker drifts slightly ahead or behind, you may see a small correction (e.g., 07 → 05 → 06) when the poll value arrives. This is normal and ensures accuracy over long recordings.

### Auto-record starts a new recording when one is already running

- This was a known issue that has been fixed. If the camera is already recording when the ride starts, ClipRide detects this and skips the auto-start to avoid resetting the duration counter.

### Camera goes to sleep during a ride

- The keep-alive mechanism sends a BLE setting write every 60 seconds to prevent camera sleep
- If the camera still goes to sleep, it may indicate a BLE communication issue. The reconnect loop will attempt to re-establish the connection.
- On HERO13, this may be related to the known BLE wake bug. Power cycle the camera.

## Project Structure

```
clipride/
├── app/
│   ├── build.gradle.kts                    App build configuration
│   ├── proguard-rules.pro                  ProGuard/R8 rules
│   └── src/main/
│       ├── kotlin/com/clipride/
│       │   ├── ble/                         BLE communication layer
│       │   ├── karoo/                       Karoo extension integration
│       │   │   ├── datatypes/               Glance-based data fields
│       │   │   │   └── glance/              Shared Glance components
│       │   │   └── handlers/                Ride event handlers
│       │   ├── di/                          Hilt dependency injection
│       │   ├── ui/                          Compose UI (settings, pairing)
│       │   │   └── components/              Shared UI components
│       │   └── util/                        Utilities
│       ├── res/
│       │   ├── values/strings.xml           All user-facing strings
│       │   ├── values/colors.xml            Color resources (synced with Color.kt)
│       │   ├── drawable/                    Icons and drawables
│       │   └── xml/extension_info.xml       Karoo extension manifest (DataTypes, BonusActions)
│       └── AndroidManifest.xml
├── gradle/
│   └── libs.versions.toml                   Version catalog
├── build.gradle.kts                         Root build configuration
├── settings.gradle.kts                      Gradle settings and repositories
└── gradle.properties                        Gradle JVM and project properties
```

## Contributing

Contributions are welcome! Please:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Build the karoo-ext dependency if you haven't already (see [From Source](#from-source) instructions)
4. Make your changes
5. Test on a Karoo device (or emulator for UI-only changes)
6. Submit a pull request

### Development Notes

- All user-facing strings must go through `res/values/strings.xml` — no hardcoded strings in activities or data fields
- Color values in `res/values/colors.xml` must stay synchronized with `ui/theme/Color.kt` and `karoo/datatypes/glance/GlanceColors.kt`
- Data fields use Jetpack Glance `startView` (not `startStream`, which requires device registration in the Karoo device system)
- The `extension_info.xml` file defines all DataTypes and BonusActions that Karoo discovers

## License

MIT License — see [LICENSE](LICENSE) for details.

## Legal

This product is not affiliated with, endorsed by, or in any way associated with GoPro, Inc. GoPro, HERO, and their respective logos are trademarks or registered trademarks of GoPro, Inc.

Hammerhead and Karoo are trademarks of Hammerhead Navigation Inc.
