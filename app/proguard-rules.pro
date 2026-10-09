# Called from native code.
-keep class dev.hardline.usb.UsbNative { *; }
-keep interface dev.hardline.usb.VideoListener { *; }
-keep interface dev.hardline.usb.AudioListener { *; }
-keepclassmembers class * implements dev.hardline.usb.VideoListener { *; }
-keepclassmembers class * implements dev.hardline.usb.AudioListener { *; }
# JavaMail and commons-net use reflection and optional classes.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn javax.security.**
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn org.apache.commons.net.**
