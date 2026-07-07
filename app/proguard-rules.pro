# 默认 ProGuard 规则
-keep class com.netmusic.player.data.** { *; }
-keep class com.netmusic.player.player.PlaybackState$* { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
