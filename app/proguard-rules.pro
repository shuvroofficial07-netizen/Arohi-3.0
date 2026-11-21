# AROHI release proguard rules
-keep class com.arohi.ai.brain.** { *; }
-keep class com.arohi.ai.smarthome.** { *; }
-keep class com.arohi.ai.pc.** { *; }
-keep class org.eclipse.paho.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn org.eclipse.paho.**
