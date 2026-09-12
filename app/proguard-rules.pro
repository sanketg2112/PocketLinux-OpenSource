# ProGuard rules for LinuxMobile app

# Keep native methods and classes containing them to prevent JNI UnsatisfiedLinkError
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep everything in com.sg.linuxgo.x11 since it is accessed heavily via JNI string lookups (FindClass)
-keep class com.sg.linuxgo.x11.** { *; }

# Keep TerminalBridge class and its members for native method linking
-keep class com.sg.linuxgo.TerminalBridge { *; }

# Keep ContainerConfig and ContainerCardStats to prevent R8 serialization/deserialization bugs
-keep class com.sg.linuxgo.ContainerConfig { *; }
-keep class com.sg.linuxgo.ui.components.ContainerCardStats { *; }

# Keep enums that are serialized or passed as strings via broadcasts/prefs
-keepclassmembers enum com.sg.linuxgo.Bootstrap$SetupStep { *; }
-keepclassmembers enum com.sg.linuxgo.Bootstrap$InstallPhase { *; }
