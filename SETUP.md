# OpenReplay Android Tracker - Setup Guide

## Configuring API Keys Securely

This project uses secure key management to keep your OpenReplay credentials safe.

### Quick Setup

1. **Create `local.properties` file** in the project root (if it doesn't exist):
   ```properties
   # This file is gitignored - safe for secrets
   sdk.dir=/path/to/your/android/sdk
   
   # Add your OpenReplay credentials here:
   OR_SERVER_URL=https://your-openreplay-instance.com/ingest
   OR_PROJECT_KEY=your_actual_project_key_here
   ```

2. **Sync Gradle** - The build system will automatically inject these values into `BuildConfig`

3. **Run the app** - Keys are accessed via `BuildConfig.OR_PROJECT_KEY` and `BuildConfig.OR_SERVER_URL`

### Alternative: Environment Variables

For CI/CD pipelines, set environment variables:

```bash
export OR_SERVER_URL="https://your-server.com/ingest"
export OR_PROJECT_KEY="your_project_key"
```

The build system checks environment variables if `local.properties` values are not found.

### Security Notes

✅ **DO:**
- Store keys in `local.properties` (gitignored)
- Use environment variables in CI/CD
- Keep `local.properties` private

❌ **DON'T:**
- Commit `local.properties` to version control
- Hardcode keys in source files
- Share your project key publicly

### Verification

After setup, the app will:
- ✅ Start successfully with your keys and track sessions
- ⚠️ Run without tracking if keys are missing (logs warning: `OR_PROJECT_KEY not configured. Tracking disabled.`)

This allows the app to run normally during development while reminding you to configure credentials when you're ready to enable tracking.

## Default Configuration

The tracker is configured with these defaults in the sample app:

```kotlin
OROptions(
    analytics = true,      // Track user interactions
    screen = true,         // Capture screenshots
    logs = true,           // Capture logs
    wifiOnly = false,      // Send over cellular too
    debugLogs = true       // Enable debug logging
)
```

Adjust these in `MainActivity.kt` based on your needs.
