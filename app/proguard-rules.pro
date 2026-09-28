# 这个 App 没有反射、没有 JNI，清单里声明的组件 R8 会自动保留，
# 所以这里只需要留一条：ContentProvider 与 Activity 都是按类名从清单实例化的。
-keep class com.ice.scan.share.ScanFileProvider { *; }
-keep class com.ice.scan.ui.** { *; }