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

## Input Tracking

Input tracking is **automatic** when you enable analytics. The tracker automatically discovers and tracks all EditText fields in your app.

### How It Works

When `analytics = true` in OROptions:
1. The tracker scans the view hierarchy when each activity is displayed
2. All EditText fields are automatically discovered
3. Input tracking is set up with smart defaults:
   - **Label**: Uses hint text → content description → view ID
   - **Masking**: Automatically enabled for password input types
   - **Tracking**: Captures on focus loss or keyboard action (Done/Next/Send)

### No Code Required

```kotlin
// That's it! All EditText fields are now tracked automatically
// Password fields are automatically masked
```

### Exclude Specific Fields

```kotlin
import com.openreplay.tracker.listeners.excludeFromTracking

class MyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Exclude internal or non-user-facing fields
        binding.debugField.excludeFromTracking()
        binding.adminNotesField.excludeFromTracking()
    }
}
```

### Manual Override (Optional)

You can still manually configure specific fields:

```kotlin
import com.openreplay.tracker.listeners.trackTextInput

// Override auto-tracking with custom settings
binding.specialField.trackTextInput(
    label = "custom_field_name",
    masked = true
)
```

### When Input is Captured

The tracker sends an `ORMobileInputEvent` when:
- User **loses focus** from the field (touches elsewhere)
- User presses **Done/Next/Send** on the keyboard

### Automatic Password Masking

Password fields are **automatically detected and masked**:
- `TYPE_TEXT_VARIATION_PASSWORD`
- `TYPE_TEXT_VARIATION_WEB_PASSWORD`  
- `TYPE_NUMBER_VARIATION_PASSWORD`

These input types always send "***" instead of the actual value for security.

## Screenshot Sanitization

To prevent sensitive data from appearing in session replay screenshots:

```kotlin
import com.openreplay.tracker.listeners.sanitize

// Apply cross-stripe mask over this field in screenshots
binding.creditCardField.sanitize()
binding.ssnField.sanitize()
```

**Important:** 
- `sanitize()` only affects **screenshots** (visual masking)
- `trackTextInput(masked = true)` only affects **input events** (data masking)
- For complete privacy, use **both** together:

```kotlin
binding.creditCardField.apply {
    trackTextInput(label = "credit_card", masked = true)  // Mask input data
    sanitize()                                             // Mask in screenshots
}
```
