# Tink references errorprone annotations that aren't shipped at runtime
-dontwarn com.google.errorprone.annotations.**

# [T-android-vad-jni-keep / GH#250] RealTimeCutVAD's C++ layer calls back into
# VADWrapper BY NAME through JNI (GetMethodID "onVoiceStart" / "onVoiceEnd" /
# "onVoiceDidContinue"). Those three are PRIVATE Java methods with no Java-side
# caller, so R8 sees them as dead and renames them — verified in the shipped
# 1.12 release dex, where they had become b()V / c()V / d()V and the `callback`
# field was removed outright. The native lookup then fails with
#
#   java.lang.NoSuchMethodError: no non-static method
#   "Lio/codeconcept/realtimecutvadlibrary/VADWrapper;.onVoiceStart()V"
#
# thrown from processAudio (a native method), which is exactly the reported
# crash: every release user who tapped the mic got "Voice detection failed"
# before any STT provider was even contacted.
#
# proguard-android-optimize.txt keeps `native` method DECLARATIONS and @Keep,
# but nothing protects an ordinary Java method that only native code invokes.
#
# Kept whole rather than member-scoped: the library is five classes, so the
# size cost is negligible, whereas a narrow rule that missed a member the
# native side also touches (the `vadInstance` handle, for one) would degrade
# into a subtler runtime failure.
#
# NOTE FOR VERIFICATION: debug builds don't minify, so this bug is invisible
# there. Any change here must be checked against an assembleRelease APK.
-keep class io.codeconcept.realtimecutvadlibrary.** { *; }

# [MINIS-OZEL] This fork ships DEV_TOOLS=true in RELEASE, so the on-device
# debug server and its JSON-RPC method registry are part of a minified build for
# the first time. Upstream never needed a rule here because release had
# DEV_TOOLS=false and R8 was free to delete the whole package as unreachable.
#
# The handler resolves methods through its own registry rather than through
# direct calls from Kotlin, so R8 cannot see the edges: without this rule a
# release build would start the server and then answer -32601 "method not
# found" to every request, or strip the classes outright. Keeping the package
# costs a few hundred KB and is the difference between an inspectable install
# and a black box.
-keep class com.openminis.app.debug.** { *; }
-keepclassmembers class com.openminis.app.debug.** { *; }
