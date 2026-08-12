# ML Kit ships its model plumbing behind reflection-heavy generated code. Keeping
# the pose-detection surface prevents R8 from stripping classes the native layer
# resolves by name at runtime.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# The accessibility service and lock activity are instantiated by the framework
# from the manifest, so R8 cannot see a call site for them.
-keep class com.fitscroll.app.block.FitScrollAccessibilityService { *; }
-keep class com.fitscroll.app.block.LockActivity { *; }
