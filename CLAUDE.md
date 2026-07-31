# Roam — dev notes

GPS speedometer whose only real job is showing speed without burning the OLED.
Native Kotlin, no AndroidX, no Play Services — `android.app.Activity` plus one custom view.

## Build

`JAVA_HOME` on this machine points at JDK 1.8, which AGP 8.9 rejects. Set it per build:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot"
.\gradlew.bat assembleDebug
```

APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Layout of the thing

- `SpeedView` draws everything on a canvas (digits, unit, max line) so the whole block can be
  placed anywhere on screen. Position comes from an R2 low-discrepancy sequence indexed by
  `floor(elapsed / moveIntervalSec)`, with a 1.1 s eased slide between slots.
- The redraw ticker idles at 250 ms and drops to 16 ms only while sliding.
- `MainActivity` owns location, prefs, brightness and the settings panel.

## Testing speed on the emulator

`geo fix` carries no speed, so the readout stays at 0. Use an NMEA RMC sentence instead —
field 7 is knots:

```powershell
adb -s emulator-5562 emu geo nmea '$GPRMC,081836,A,5540.5665,N,01234.0000,E,032.4,054.7,181026,003.1,W*6A'
```

Speed decays back to 0 once the sentences stop, because the watchdog treats a stale fix as
stationary.
