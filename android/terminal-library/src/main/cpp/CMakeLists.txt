cmake_minimum_required(VERSION 3.22.1)

project("terminal" C)

add_library(pty SHARED pty.c)
target_link_libraries(pty log)

# A program, not a library, but it ships as one: only the native library
# directory is executable no matter which API level the app targets, and only
# files named lib*.so are installed there.
add_executable(syscall-shim syscall-shim.c)
set_target_properties(syscall-shim PROPERTIES
    OUTPUT_NAME "syscallshim"
    PREFIX "lib"
    SUFFIX ".so")
