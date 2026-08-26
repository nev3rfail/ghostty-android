//! The C declarations of the JNI boundary, translated once.
//!
//! Each `@cImport` produces its own types, so a `JNIEnv` translated in one
//! file is not the `JNIEnv` translated in another and a handle cannot be
//! passed between them. Every file that carries JNI types across a file
//! boundary shares this translation.

pub const c = @cImport({
    @cInclude("jni.h");
    @cInclude("GLES3/gl31.h");
    @cInclude("android/log.h");
});
