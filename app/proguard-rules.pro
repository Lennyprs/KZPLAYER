# Conserver Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# v84 Live preview / EPG
-keep class com.kzplayer.app.LivePreviewActivity { *; }
-keep class com.kzplayer.app.EpgEntry { *; }

# v349 Bande-annonce : pont JavaScript de la WebView (noms utilises par le JS).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.kzplayer.app.TrailerActivity { *; }
-keep class com.kzplayer.app.TrailerActivity$Bridge { *; }
