const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    // Validate this is an Android target
    const target_info = target.result;
    if (target_info.os.tag != .linux or target_info.abi != .android) {
        std.log.warn("Warning: Target should be Android (e.g., aarch64-linux-android)", .{});
    }

    // Get FreeType dependency
    // Note: Using Twemoji COLR font which doesn't need libpng
    const freetype_dep = b.dependency("freetype", .{
        .target = target,
        .optimize = optimize,
        .@"enable-libpng" = false,
    });

    // Get libghostty-vt dependency
    const vt_dep = b.dependency("ghostty-vt", .{
        .target = target,
        .optimize = optimize,
    });

    // Create a module for the library
    const lib_mod = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,

        // Required for JNI.
        .link_libc = true,
    });

    // Add FreeType module
    //
    // Bionic annotates array parameters with nullability qualifiers, which the
    // C frontend behind @cImport rejects on a type that is not a pointer. The
    // qualifiers only document intent to a C compiler and nothing about them
    // survives translation, so they are defined away.
    const freetype_mod = freetype_dep.module("freetype");
    freetype_mod.addCMacro("_Nonnull", "");
    freetype_mod.addCMacro("_Nullable", "");
    lib_mod.addImport("freetype", freetype_mod);

    // Add libghostty-vt module (using the Zig module, not C ABI)
    lib_mod.addImport("ghostty-vt", vt_dep.module("ghostty-vt"));

    // Create the shared library for Android
    const lib = b.addLibrary(.{
        .name = "ghostty_renderer",
        .root_module = lib_mod,
        .linkage = .dynamic,
    });

    // Link FreeType library
    lib_mod.linkLibrary(freetype_dep.artifact("freetype"));

    // Note: We need OpenGL ES 3.1 symbols, but can't link them during cross-compilation.
    // The symbols will be left undefined and must be resolved by the Android dynamic linker.
    // We'll use a post-build step to add libGLESv3.so to DT_NEEDED section.

    // Add include paths for libghostty-vt headers (if needed in future)
    const ghostty_include = b.path("../../libghostty-vt/include");
    lib_mod.addIncludePath(ghostty_include);

    // Install the shared library
    b.installArtifact(lib);

    // Create a test step (for future unit tests)
    const test_mod = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });

    const lib_unit_tests = b.addTest(.{
        .name = "renderer_tests",
        .root_module = test_mod,
    });

    const run_lib_unit_tests = b.addRunArtifact(lib_unit_tests);
    const test_step = b.step("test", "Run unit tests");
    test_step.dependOn(&run_lib_unit_tests.step);
}
