# Rider mobile app

Android app for riders of the E-Bike/Scooter Sharing Fleet Management System.

**Week 1 status:** app skeleton and rider navigation flow, running on in-memory synthetic data. It does not call a backend yet.

## Rider flow

```
Available vehicles -> Vehicle details -> Active ride -> Ride summary -> Available vehicles
```

| Screen | Route | What the rider does |
|---|---|---|
| Available vehicles | `vehicles` | Picks a vehicle, or resumes a ride from the banner |
| Vehicle details | `vehicles/{vehicleId}` | Checks battery and status, starts a rental |
| Active ride | `rentals/{rentalId}/active` | Returns the vehicle |
| Ride summary | `rentals/{rentalId}/summary` | Sees times and duration, finishes |

The vehicle list always stays at the bottom of the back stack, and each rental step replaces the one before it. Pressing Back during a ride therefore goes to the list, not to "Start rental", and the ride can be reopened from the banner.

## Requirements

- JDK 17 on `PATH`, or as `JAVA_HOME`
- Android SDK. Gradle downloads platform 35 on the first build if the SDK licences are accepted
- An Android Studio release that supports Android Gradle Plugin 8.7 or newer (Ladybug or later)
- A device or emulator running Android 8.0 (API 26) or newer

## Setup

Create `rider-app/local.properties` with your SDK location. This file is ignored by Git. You can skip this step if `ANDROID_HOME` is set, or if you open the project in Android Studio.

```properties
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

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

## Project layout

```
app/src/main/java/com/cebp/rider/
  model/        Vehicle and Rental
  data/         RiderRepository interface and its fake implementation
  navigation/   Routes and the back-stack rules
  ui/screens/   One file per screen
  ui/components/ Shared scaffold, loading and label helpers
```

## Technology versions

Proposed for the Week 1 freeze. The definitive list is in `gradle/libs.versions.toml`.

| Technology | Version |
|---|---|
| Kotlin | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9 |
| Jetpack Compose BOM | 2024.12.01 |
| Navigation Compose | 2.8.5 |
| minSdk / targetSdk | 26 / 35 |

## Connecting the backend

Screens only use `RiderRepository`. When the OpenAPI contract is published:

1. Update `model/Models.kt` to match the contract. Its fields are provisional.
2. Add an HTTP implementation of `RiderRepository`, plus the `INTERNET` permission.
3. Change the single line in `RiderApplication` that creates the repository.

## Known limitations

- Vehicle and rental data is in memory and resets when the app process ends.
- There is no location, map or distance data yet (planned for Week 4).
- `RiderRepository` has no network failure handling yet, because the fake cannot fail on reads.
- Rental IDs and idempotency rules in the fake are assumptions until the API contract defines them.
