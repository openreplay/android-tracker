# OpenReplay Android Tracker

Android SDK for session replay and analytics tracking.

## Setup

> **📖 For detailed setup instructions, see [SETUP.md](SETUP.md)**

### Quick Start

The sample app requires configuration before running:

**Option 1: Local Properties (Recommended)**

Create `local.properties` in the project root:
```properties
OR_SERVER_URL=https://your-server.com/ingest
OR_PROJECT_KEY=your-project-key
```

**Option 2: Environment Variables**

```bash
export OR_SERVER_URL="https://your-server.com/ingest"
export OR_PROJECT_KEY="your-project-key"
./gradlew assembleDebug
```

**Option 3: Gradle Properties**

Add to your `~/.gradle/gradle.properties`:
```properties
OR_SERVER_URL=https://your-server.com/ingest
OR_PROJECT_KEY=your-project-key
```

ℹ️ **Note:** If `OR_PROJECT_KEY` is not configured, the app will run normally but tracking will be disabled. A warning will be logged to help developers identify the missing configuration.

### Build Configuration

The project uses Gradle version catalogs and includes:

- **Debug build**: Includes `.debug` suffix and debug logging enabled
- **Release build**: ProGuard enabled with R8 optimization
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)

### Dependencies

Key dependencies:
- Kotlin 2.0.0
- AndroidX Core KTX 1.13.1
- Gson 2.10.1
- Apache Commons Compress 1.26.1
- Jetpack Compose (for tracker UI)

## Building

```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

## Sample App

The `app` module contains a sample application demonstrating tracker integration:

- **Session tracking** - Automatic session recording
- **User events** - Custom events and metadata
- **GraphQL monitoring** - Query and mutation tracking
- **Network tracking** - HTTP request/response capture
- **Touch events** - Click and swipe gesture recording
- **Screenshot sanitization** - Mask sensitive UI elements
- **Analytics events** - All mobile event types covered

### Testing Screenshot Sanitization

The Home tab includes a live demo of screenshot masking:

- **Regular Field**: Visible in screenshots
- **Sanitized Field**: Masked with cross-stripes in screenshots
- **Toggle Button**: Switch sanitization on/off to see the difference

## Publishing

The tracker library is configured for Maven publishing via JitPack.

### Current Version
Version: 1.1.4

```gradle
implementation("com.github.openreplay:android-tracker:1.1.4")
```
