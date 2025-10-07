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
- **Input tracking** - Automatic EditText field tracking
- **GraphQL monitoring** - Query and mutation tracking
- **Network tracking** - HTTP request/response capture
- **Touch events** - Click and swipe gesture recording
- **Screenshot sanitization** - Mask sensitive UI elements
- **Analytics events** - All mobile event types covered

### Input Tracking

Input tracking is **automatic** when `analytics = true`. All EditText fields are automatically tracked when an activity is displayed.

**Features:**
- ✅ **Auto-discovery**: Finds all EditText fields in the view hierarchy
- ✅ **Smart labeling**: Uses hint text, content description, or view ID
- ✅ **Password detection**: Automatically masks password input types
- ✅ **Opt-out support**: Exclude specific fields from tracking

**Automatic Tracking:**
```kotlin
// No code needed - EditText fields are automatically tracked!
// Password fields are automatically masked
```

**Exclude Specific Fields:**
```kotlin
import com.openreplay.tracker.listeners.excludeFromTracking

// Opt-out of tracking for sensitive fields
binding.internalNotesField.excludeFromTracking()
```

**Manual Tracking (Optional):**
```kotlin
import com.openreplay.tracker.listeners.trackTextInput

// Override auto-tracking with custom settings
binding.specialField.trackTextInput(label = "custom_label", masked = true)
```

The tracker captures input when the user:
- Loses focus from the field
- Presses Done/Next/Send on the keyboard

### Screenshot Sanitization

The Home tab includes a live demo of screenshot masking:

```kotlin
import com.openreplay.tracker.listeners.sanitize

// Mask a field in screenshots (visual only)
binding.creditCardField.sanitize()
```

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
