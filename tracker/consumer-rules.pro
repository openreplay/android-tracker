-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

-keep public class com.openreplay.tracker.OpenReplay { *; }
-keep public class com.openreplay.tracker.ORTracker { *; }
-keep public class com.openreplay.tracker.models.** { *; }
-keep public interface com.openreplay.tracker.** { *; }

-keepclassmembers class com.openreplay.tracker.** {
    public <init>(...);
    public <methods>;
}

-dontwarn org.apache.commons.compress.**
-dontwarn java.nio.file.Path
-dontwarn java.nio.file.OpenOption
-dontwarn java.nio.channels.SeekableByteChannel

-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
