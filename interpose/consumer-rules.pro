# Rules handed to whoever consumes the AAR.
#
# Interpose lives on the androidTest classpath, so these only ever reach a build that
# minifies its *test* APK — uncommon, but worth covering, because every failure mode here
# is silent. Each of the three things Interpose looks up by name degrades to "unsupported"
# when it cannot be found, which is indistinguishable from the condition it tests for: an
# obfuscated build would simply capture less and say nothing.

# Named as a string, never referenced as a type: testInstrumentationRunner in the build
# file, `am instrument` on the command line, and <instrumentation> in the test manifest.
-keep class eu.buney.interpose.InterposeRunner { public protected *; }

# WebView capture asks whether androidx.webkit is present with Class.forName before it
# loads anything typed, so that an app without a WebView carries no dependency. The typed
# references in the implementation keep the class alive, but renaming it still breaks the
# probe — and a false "androidx.webkit absent" costs the WebView's traffic silently.
-keepnames class androidx.webkit.ProxyController

# The HTTP/2 server path drives okhttp3.internal.http2, which carries no compatibility
# promise, so Interpose checks the shape of that API before offering h2 at all. R8 renaming
# the method looks exactly like the incompatible-OkHttp case this guards against: h2 is
# dropped for the whole run and intercepted traffic quietly falls back to HTTP/1.1.
-keepnames class okhttp3.internal.http2.Http2Connection$Builder
-keepclassmembernames class okhttp3.internal.http2.Http2Connection$Builder {
    *** socket(...);
}
