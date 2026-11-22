# Rider mobile app

Android app for riders of the E-Bike/Scooter Sharing Fleet Management System.

**Week 3 status:** complete rental and return flow through `fleet-management-service` and its
database, following the fleet API contract ([`api/fleet-api.yaml`](../api/fleet-api.yaml)):
nearby discovery with type and radius filters, battery and status display with measurement
age, faults during a ride, and every rental error. Demo mode (in-memory data that follows the
backend's rules) is the default; a build setting switches to the backend.

### What the rider can do

- **Find vehicles:** nearby rentable vehicles, closest first, filtered by type (All, E-bikes,
  E-scooters) and search radius (500 m, 1 km, 2 km). Pull down to refresh; the list also
  refreshes itself every 30 seconds while visible.
- **Check a vehicle:** battery level with a colour bar (green from 50%, amber from 20%, red
  below), when the battery was measured, with a warning when the reading is over 15 minutes old,
  and the status. A vehicle that can't be rented says why: someone else is riding it, battery
  below 20%, or maintenance.
- **Ride:** a ride timer, the vehicle's current battery, and a warning if the vehicle reports a
  fault during the ride (the ride stays returnable, as in the contract). The screen refreshes
  every 30 seconds. A ride that already ended elsewhere links to its summary.
- **Errors:** each contract error has its own message. After a refusal because the vehicle
  changed, the screen reloads it, so the rider sees its current status. "You already have a ride
  in progress" offers **Go to my ride**. A failed refresh keeps the last data on screen and
  says it couldn't update.

## Rider flow

```
Available vehicles -> Vehicle details -> Active ride -> Ride summary -> Available vehicles
```

| Screen | Route | What the rider does | API calls |
|---|---|---|---|
| Available vehicles | `vehicles` | Filters and picks a nearby vehicle, or resumes a ride from the banner | `GET /rentals?riderId=…&status=ACTIVE`, `GET /vehicles/nearby?lat=…&lon=…&radiusMeters=…` |
| Vehicle details | `vehicles/{vehicleId}` | Checks battery, its age and status, starts a rental | `GET /vehicles/{id}`, `POST /rentals` |
| Active ride | `rentals/{rentalId}/active` | Watches ride time, battery and faults, returns the vehicle | `GET /rentals/{id}`, `GET /vehicles/{id}`, `POST /rentals/{id}/return` |
| Ride summary | `rentals/{rentalId}/summary` | Sees times, duration (to the second) and battery, finishes | `GET /rentals/{id}`, `GET /vehicles/{id}` |

The ride screens call `GET /vehicles/{id}` because the `Rental` schema only has `vehicleId`.

The vehicle list always stays at the bottom of the back stack, and each rental step replaces the one before it. Pressing Back during a ride therefore goes to the list, not to "Start rental", and the ride can be reopened from the banner.

## How the app follows the contract

- **IDs** are the contract's prefixed strings (`veh_001`, `rnt_001`), treated as opaque.
- **Freshness:** battery age comes from `lastBatteryAt`, never from `lastTelemetryAt`, which the
  contract says only records activity.
- **Rider identity:** there are no accounts, so each installation generates a `riderId`
  (`rider-<UUID>`) and keeps it. A ride in progress is found again after the app restarts, and
  two emulators act as two different riders.
- **Starting a rental** sends an `Idempotency-Key`. The key is reused when the rider taps again
  after a network failure, so a retry cannot start a second rental. After a refusal nothing was
  created, so the next tap gets a new key.
- **Returning** sends where the vehicle was parked. Returning is idempotent in the contract, so
  retrying is safe.
- **Errors:** messages are chosen from the contract's `Error.code` (`VEHICLE_NOT_AVAILABLE`,
  `BATTERY_TOO_LOW`, `RIDER_HAS_ACTIVE_RENTAL`, `NOT_FOUND`, `IDEMPOTENCY_KEY_REUSED`,
  `VALIDATION_ERROR`, `MISSING_IDEMPOTENCY_KEY`), never from the server's text. Network failures
  and server errors show a message, and screens that fail to load offer **Try again**.
- **Demo data** checks a rental in the backend's order: vehicle status, then battery, then the
  rider's active ride. Like the backend, it keeps vehicles under 20% in maintenance.
- **Location:** a fixed synthetic position, the centre of `zone_001` (City Center, 48.2100,
  16.3600). See `data/RiderLocation.kt`. Device location is planned for Week 4.
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

### Running against the local backend

From the repository root, on a branch that contains `fleet-management-service`, start it with
PostgreSQL and Kafka:

```bash
docker compose up --build -d --wait fleet-management-service
```

The `dev` profile only creates zones, so register some vehicles near the rider's position,
for example:

```bash
curl -X POST -H 'Content-Type: application/json' http://localhost:8080/api/v1/vehicles \
  -d '{"label":"EB-001","type":"E_BIKE","batteryPercent":92,"latitude":48.2102,"longitude":16.3598}'
```

Stop it with `docker compose down`; add `-v` to also delete the database.

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
| `FakeRiderRepositoryTest` | Computer | Demo data follows the backend's rules: nearby radius and order, measurement times, check order, one active ride per rider, maintenance refusals, faults during a ride, idempotent start and return |
| `HttpRiderRepositoryTest` | Computer, with OkHttp MockWebServer | The requests the app sends (paths, query including `radiusMeters`, `Idempotency-Key`, bodies) and how it reads the contract's example responses, timestamps and errors |
| `AgeTest`, `DurationPartsTest` | Computer | Rounding of battery-reading age and the stale limit; splitting ride durations |
| `RiderFlowTest` | Device or emulator | The screens: full flow, Back behaviour, type and radius filters, empty results, battery age, not-rentable reasons, fault warning, refusals with **Go to my ride**, a vehicle that changed meanwhile, network failure and retry, failed refresh |
| `DurationTextTest` | Device or emulator | Duration wording |

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

- The position is fixed; device location and area selection are planned for Week 4.
- Updates arrive by refreshing (pull, or every 30 seconds), not pushed live; the backend's SSE
  stream is for the dashboard.
- Demo data is in memory and resets when the app process ends.
- The vehicle type filter runs in the app, because `GET /vehicles/nearby` has no type parameter.
- Ride durations are computed from the backend's start and end timestamps, to the second. Texts
  are English only.
