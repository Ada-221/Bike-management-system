# Rider mobile app

Android app for riders of the E-Bike/Scooter Sharing Fleet Management System.

**Week 2 status:** demo mode is the supported default. The optional HTTP adapter
targets the draft fleet API contract ([`api/fleet-api.yaml`](../api/fleet-api.yaml)).
The implemented backend has vehicle CRUD but **does not implement nearby, rental
or return endpoints**. Running the backend does not enable the live rider flow.
Keep `rider.backendUrl` empty for the integrated milestone and instrumentation tests.
A build setting enables the adapter only for future compatible backends.

## Rider flow

```
Available vehicles -> Vehicle details -> Active ride -> Ride summary -> Available vehicles
```

| Screen | Route | What the rider does | API calls |
|---|---|---|---|
| Available vehicles | `vehicles` | Picks a nearby vehicle, or resumes a ride from the banner | `GET /rentals?riderId=…&status=ACTIVE`, `GET /vehicles/nearby` |
| Vehicle details | `vehicles/{vehicleId}` | Checks battery and status, starts a rental | `GET /vehicles/{id}`, `POST /rentals` |
| Active ride | `rentals/{rentalId}/active` | Returns the vehicle | `GET /rentals/{id}`, `POST /rentals/{id}/return` |
| Ride summary | `rentals/{rentalId}/summary` | Sees times, duration (to the second) and battery, finishes | `GET /rentals/{id}` |

The active ride and summary screens also call `GET /vehicles/{id}` to show the vehicle's label,
because the `Rental` schema only has `vehicleId`.

The vehicle list always stays at the bottom of the back stack, and each rental step replaces the one before it. Pressing Back during a ride therefore goes to the list, not to "Start rental", and the ride can be reopened from the banner.

## How the app follows the contract

- **IDs** are treated as opaque strings. The team agreed on prefixed IDs such as `veh_001`, while
  the v0.1 contract still says UUID; the app works with either.
- **Rider identity:** there are no accounts, so each installation generates a `riderId`
  (`rider-<UUID>`) and keeps it. A ride in progress is found again after the app restarts, and
  two emulators act as two different riders.
- **Starting a rental** sends an `Idempotency-Key`. The key is created once per visit to the
  details screen and reused when the rider taps again after a network failure, so a retry cannot
  start a second rental.
- **Returning** sends where the vehicle was parked. Returning is idempotent in the contract, so
  retrying is safe.
- **Errors:** messages are chosen from the contract's `Error.code` (`VEHICLE_NOT_AVAILABLE`,
  `BATTERY_TOO_LOW`, `RIDER_HAS_ACTIVE_RENTAL`, `NOT_FOUND`), never from the server's text.
  Network failures and server errors show a message, and screens that fail to load offer
  **Try again**.
- **Location:** Week 2 uses a fixed synthetic position, the centre of the "Campus" zone from the
  contract examples (48.2100, 16.3600). See `data/RiderLocation.kt`.
- Every request carries an `X-Correlation-Id` header, which the contract allows, to help trace
  requests in backend logs.

## Requirements

- JDK 17 on `PATH`, or as `JAVA_HOME`. Gradle 8.9 does not run on Java 23 or newer; in Android
  Studio, set **Settings → Build Tools → Gradle → Gradle JDK** to a JDK 17.
- Android SDK. Gradle downloads platform 35 on the first build if the SDK licences are accepted
- An Android Studio release that supports Android Gradle Plugin 8.7 or newer (Ladybug or later)
- A device or emulator running Android 8.0 (API 26) or newer

## Setup

Create `rider-app/local.properties` with your SDK location. This file is ignored by Git. You can skip this step if `ANDROID_HOME` is set, or if you open the project in Android Studio.

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

### Choosing the data source

With no setting, the app uses in-memory demo data. To use a backend, add its API base URL to
`local.properties`, then rebuild:

```properties
# The host machine's port 8080, as seen from the Android emulator
rider.backendUrl=http://10.0.2.2:8080/api/v1/
```

Or pass it for one build: `./gradlew installDebug -Prider.backendUrl=http://10.0.2.2:8080/api/v1/`.

Debug builds allow plain HTTP only to `10.0.2.2`, `localhost` and `127.0.0.1`
(`app/src/debug/res/xml/network_security_config.xml`). A physical phone reaching a laptop over
Wi-Fi needs the laptop's address added there.

Before the fleet service exists, the contract's mock server can stand in for it, from the
repository root: `npx @stoplight/prism-cli mock api/fleet-api.yaml` (port 4010, so use
`http://10.0.2.2:4010/`). It returns the contract examples, so the data does not change after
a rental.

## Commands

Run these from `rider-app/`. On macOS or Linux, use `./gradlew` instead of `gradlew.bat`.

```bash
gradlew.bat assembleDebug
```

```bash
gradlew.bat testDebugUnitTest
```

Run the rider flow tests with a device or emulator connected:

```bash
gradlew.bat connectedDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Tests

| Test | Where it runs | What it covers |
|---|---|---|
| `FakeRiderRepositoryTest` | Computer | Demo data follows the contract's rules: nearby filtering and order, one active ride per rider, battery and maintenance refusals, idempotent start and return |
| `HttpRiderRepositoryTest` | Computer, with OkHttp MockWebServer | The requests the app sends (paths, query, `Idempotency-Key`, bodies) and how it reads the contract's example responses and errors |
| `RiderFlowTest` | Device or emulator | The screens: full flow, Back behaviour, refusals, non-rentable vehicles, network failure and retry |

## Project layout

```
app/src/main/java/com/cebp/rider/
  model/          Vehicle and Rental, matching the contract schemas
  data/           RiderRepository interface, RiderException, demo implementation,
                  rider ID store and the rider's position
  data/remote/    Retrofit API definition and the HTTP implementation
  navigation/     Routes and the back-stack rules
  ui/screens/     One file per screen
  ui/components/  Shared scaffold, loading/error/retry and label helpers
app/src/debug/    Debug-only network settings for a local backend
```

## Technology versions

The definitive list is in `gradle/libs.versions.toml`.

| Technology | Version |
|---|---|
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9 |
| Jetpack Compose BOM | 2024.12.01 |
| Navigation Compose | 2.8.5 |
| Retrofit (with kotlinx.serialization converter) | 2.11.0 |
| OkHttp (and MockWebServer for tests) | 4.12.0 |
| kotlinx.serialization JSON | 1.7.3 |
| Espresso / AndroidX Test JUnit | 3.7.0 / 1.3.0 |
| minSdk / targetSdk | 26 / 35 |

Retrofit, OkHttp and kotlinx.serialization were added in Week 2 for the backend connection.
Espresso moved from 3.6.1 to 3.7.0 because every UI test failed with 3.6.1 on an API 37
emulator (`NoSuchMethodException: InputManager.getInstance`).

## Known limitations

- The real fleet service has not been run against yet; the HTTP code is checked against the
  contract with tests and a local stub. Field names may still change with the contract.
- The position is fixed; device location and area selection are planned for Week 4.
- Demo data is in memory and resets when the app process ends.
- The vehicle list loads once per visit and does not refresh live; pull-to-refresh or polling is
  not implemented.
- Ride durations are computed from the backend's start and end timestamps, to the second
  (for example "1 minute and 36 seconds"). The duration wording is English only.
