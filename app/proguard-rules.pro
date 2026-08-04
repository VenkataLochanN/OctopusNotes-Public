# Add project specific ProGuard rules here.
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep readable line numbers in release crash reports (worth the few KB for a solo dev),
# while still hiding the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# PDFBox-Android (com.tom-roush:pdfbox-android)
# Loads fonts, encodings and glyph-list classes reflectively at render time.
# R8 can't see those references, so it would strip them and PDF rendering would
# crash the first time a document is opened. Keep the whole library + fontbox,
# and silence warnings about its optional javax/AWT deps that don't exist on Android.
# ---------------------------------------------------------------------------
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn java.awt.**
-dontwarn javax.**

# ---------------------------------------------------------------------------
# Room entities. Room keeps its generated DAO/impl itself, but the @Entity model
# classes are referenced by name in generated SQL binding — keep them intact.
# ---------------------------------------------------------------------------
-keep @androidx.room.Entity class * { *; }
-keep class com.lochan.octopusnotes.Notebook { *; }
-keep class com.lochan.octopusnotes.Folder { *; }
-keep class com.lochan.octopusnotes.Drawing { *; }
