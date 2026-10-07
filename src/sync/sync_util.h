#ifndef CLIPSYNC_SYNC_UTIL_H
#define CLIPSYNC_SYNC_UTIL_H

// ClipSync 客户端工具函数：base64、随机 ID、毫秒时间戳。
// 仅用 C++17 标准库 + Win32，无第三方依赖。

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include <windows.h>

namespace clipsync {

inline std::string base64Encode(const std::string& data) {
    static const char* table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    out.reserve(((data.size() + 2) / 3) * 4);
    std::size_t i = 0;
    while (i + 2 < data.size()) {
        std::uint32_t n = (static_cast<unsigned char>(data[i]) << 16) |
                          (static_cast<unsigned char>(data[i + 1]) << 8) |
                          static_cast<unsigned char>(data[i + 2]);
        out += table[(n >> 18) & 63];
        out += table[(n >> 12) & 63];
        out += table[(n >> 6) & 63];
        out += table[n & 63];
        i += 3;
    }
    if (i + 1 == data.size()) {
        std::uint32_t n = static_cast<unsigned char>(data[i]) << 16;
        out += table[(n >> 18) & 63];
        out += table[(n >> 12) & 63];
        out += "==";
    } else if (i + 2 == data.size()) {
        std::uint32_t n = (static_cast<unsigned char>(data[i]) << 16) |
                          (static_cast<unsigned char>(data[i + 1]) << 8);
        out += table[(n >> 18) & 63];
        out += table[(n >> 12) & 63];
        out += table[(n >> 6) & 63];
        out += "=";
    }
    return out;
}

inline bool base64Decode(const std::string& in, std::string& out) {
    auto val = [](char c) -> int {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '+') return 62;
        if (c == '/') return 63;
        return -1;
    };
    out.clear();
    out.reserve(in.size() / 4 * 3);
    std::uint32_t buf = 0;
    int bits = 0;
    for (char c : in) {
        if (c == '=' || c == '\r' || c == '\n') continue;
        int v = val(c);
        if (v < 0) return false;
        buf = (buf << 6) | static_cast<std::uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out += static_cast<char>((buf >> bits) & 0xFF);
        }
    }
    return true;
}

// Unix 毫秒时间戳（FILETIME → Unix）
inline std::uint64_t unixNowMs() {
    FILETIME ft;
    GetSystemTimeAsFileTime(&ft);
    ULARGE_INTEGER u;
    u.HighPart = ft.dwHighDateTime;
    u.LowPart = ft.dwLowDateTime;
    return (u.QuadPart - 116444736000000000ULL) / 10000ULL;
}

// 进程内唯一 ID（时间戳 + 计数器，足够个人同步场景；非密码学用途）
inline std::string makeSyncId() {
    static volatile long counter = 0;
    char buf[48];
    LARGE_INTEGER t;
    QueryPerformanceCounter(&t);
    InterlockedIncrement(&counter);
    sprintf_s(buf, sizeof(buf), "%llx-%x-%llx", static_cast<unsigned long long>(t.QuadPart),
              static_cast<unsigned>(GetCurrentProcessId()), static_cast<unsigned long long>(counter));
    return buf;
}

} // namespace clipsync

#endif // CLIPSYNC_SYNC_UTIL_H
