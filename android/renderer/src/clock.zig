//! The wall clock.
//!
//! Reading a clock is an IO operation, reached through an `std.Io`
//! implementation. The one this library carries for the terminal is TinyIo,
//! which answers every clock with zero, and the alternative in the standard
//! library costs a quarter of a megabyte of thread-local storage for the
//! concurrency machinery around it. Neither is worth a timestamp, so the
//! syscall is made directly.

const std = @import("std");

/// Nanoseconds since the Unix epoch.
pub fn nowNanos() i64 {
    var ts: std.c.timespec = undefined;
    if (std.c.clock_gettime(.REALTIME, &ts) != 0) return 0;
    return @as(i64, ts.sec) * std.time.ns_per_s + @as(i64, ts.nsec);
}

/// Milliseconds since the Unix epoch.
pub fn nowMillis() i64 {
    return @divTrunc(nowNanos(), std.time.ns_per_ms);
}
