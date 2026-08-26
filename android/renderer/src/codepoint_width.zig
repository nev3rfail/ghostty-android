//! Codepoint width caching layer for the Android renderer
//!
//! This module provides a cached interface to libghostty's Unicode width
//! implementation, optimizing for common cases and reducing lookups.

const std = @import("std");
const ghostty_vt = @import("ghostty-vt");
const log = std.log.scoped(.codepoint_width);

/// Width cache for fast lookup of recently used codepoints
pub const WidthCache = struct {
    const CacheSize = 4096; // Cache 4K most recent codepoints
    const Entry = struct {
        codepoint: u21,
        width: u8,
    };

    allocator: std.mem.Allocator,
    entries: []Entry,
    /// Simple hash table with linear probing
    lookup: []?usize,

    pub fn init(allocator: std.mem.Allocator) !WidthCache {
        var cache = WidthCache{
            .allocator = allocator,
            .entries = try allocator.alloc(Entry, CacheSize),
            .lookup = try allocator.alloc(?usize, CacheSize),
        };

        // Initialize lookup table to empty
        @memset(cache.lookup, null);

        // Pre-populate with ASCII range for fast access
        for (0..128) |i| {
            const cp: u21 = @intCast(i);
            _ = cache.get(cp); // This will populate the cache
        }

        return cache;
    }

    pub fn deinit(self: *WidthCache) void {
        self.allocator.free(self.entries);
        self.allocator.free(self.lookup);
    }

    /// Get the width of a codepoint, using cache if available
    pub fn get(self: *WidthCache, codepoint: u21) u8 {
        // Fast path for ASCII (always width 1 or 0 for control chars)
        if (codepoint < 128) {
            // Control characters (0x00-0x1F, 0x7F) have width 0
            if (codepoint < 0x20 or codepoint == 0x7F) {
                return 0;
            }
            return 1;
        }

        // Check cache
        const hash = @mod(codepoint, CacheSize);
        var idx = hash;
        var attempts: usize = 0;

        // Linear probing to find entry
        while (attempts < 16) : (attempts += 1) {
            if (self.lookup[idx]) |entry_idx| {
                if (self.entries[entry_idx].codepoint == codepoint) {
                    // Cache hit
                    return self.entries[entry_idx].width;
                }
            } else {
                // Empty slot - cache miss
                break;
            }
            idx = @mod(idx + 1, CacheSize);
        }

        // Cache miss - get width from libghostty
        const width = codepointWidth(codepoint);

        // Add to cache (simple replacement strategy)
        const entry_idx = @mod(codepoint, CacheSize); // Simple index
        self.entries[entry_idx] = .{
            .codepoint = codepoint,
            .width = width,
        };
        self.lookup[@mod(codepoint, CacheSize)] = entry_idx;

        return width;
    }

    /// Check if a codepoint is double-width (CJK, emoji, etc)
    pub fn isWide(self: *WidthCache, codepoint: u21) bool {
        return self.get(codepoint) == 2;
    }

    /// Check if a codepoint is zero-width (combining marks, etc)
    pub fn isZeroWidth(self: *WidthCache, codepoint: u21) bool {
        return self.get(codepoint) == 0;
    }
};

/// Get the display width of a codepoint (0, 1, or 2)
/// This is a direct interface without caching for one-off lookups
pub fn codepointWidth(codepoint: u21) u8 {
    return ghostty_vt.unicode.codepointWidth(codepoint);
}

test "WidthCache basic functionality" {
    const testing = std.testing;
    var cache = try WidthCache.init(testing.allocator);
    defer cache.deinit();

    // ASCII characters
    try testing.expectEqual(@as(u8, 1), cache.get('a'));
    try testing.expectEqual(@as(u8, 1), cache.get('Z'));
    try testing.expectEqual(@as(u8, 1), cache.get('9'));

    // Control characters
    try testing.expectEqual(@as(u8, 0), cache.get(0x00)); // NULL
    try testing.expectEqual(@as(u8, 0), cache.get(0x0A)); // LF
    try testing.expectEqual(@as(u8, 0), cache.get(0x7F)); // DEL

    // Wide characters (if supported by libghostty)
    // Note: These tests assume libghostty properly identifies these as wide
    try testing.expectEqual(@as(u8, 2), cache.get(0x4E00)); // CJK Ideograph
    try testing.expectEqual(@as(u8, 2), cache.get(0x1F600)); // Emoji
}

test "codepointWidth direct function" {
    const testing = std.testing;

    // Test direct function without cache
    try testing.expectEqual(@as(u8, 1), codepointWidth('A'));
    try testing.expectEqual(@as(u8, 0), codepointWidth(0x00));
    try testing.expectEqual(@as(u8, 2), codepointWidth(0x4E00));
}