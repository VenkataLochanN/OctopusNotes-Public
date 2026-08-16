
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep @androidx.room.Entity class * { *; }
-keep class com.lochan.octopusnotes.Notebook { *; }
-keep class com.lochan.octopusnotes.Folder { *; }
-keep class com.lochan.octopusnotes.Drawing { *; }
