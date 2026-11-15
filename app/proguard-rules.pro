# NewPipeExtractor bundles Rhino (org.mozilla.javascript), which references
# java.beans.* and javax.script.* — classes that do not exist on Android.
# Rhino's JS execution (used by NewPipeExtractor for signature deciphering)
# does not touch those code paths, so silence R8's missing-class errors.
# Rhino itself must NOT be excluded: NewPipeExtractor needs it at runtime.
-dontwarn java.beans.**
-dontwarn javax.script.**
