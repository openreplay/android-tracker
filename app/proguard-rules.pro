-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-renamesourcefileattribute SourceFile

-keep class com.openreplay.tracker.** { *; }
-keep interface com.openreplay.tracker.** { *; }

-keepclassmembers class * {
    @com.openreplay.tracker.** *;
}

-dontwarn org.apache.commons.compress.**
-dontwarn java.nio.file.**

-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

-keepclassmembers class * extends java.lang.Enum {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}