#ifndef CLIPSYNC_ZIP_UTIL_H
#define CLIPSYNC_ZIP_UTIL_H

// 最小 ZIP 编解码（store 模式不压缩，零依赖）。
// 用途：文件清单剪贴板跨设备同步（协议 docs/clip-sync-protocol.md §3.3 bundle）。
//
// 写入：标准 local file header + central directory + EOCD，压缩方法 0（store），
//       DOS 时间戳固定（同步场景不需要保留真实 mtime，避免时区歧义）。
// 读取：走 central directory 定位条目，拒绝压缩方法非 0 的条目（我们只消费自己
//       产生的包）；内置 zip-slip 防护（拒绝绝对路径 / .. / 盘符 / 反斜杠）。
// 限制：条目数 ≤ 4096，总解压大小 ≤ 32MB（调用方可再按需收紧）。

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace clipsync {

struct ZipEntry {
    std::string name;  // 相对路径，'/' 分隔，UTF-8
    std::string data;  // 条目原始字节
};

inline std::uint32_t zipCrc32(const void* data, std::size_t size) {
    static std::uint32_t table[256];
    static bool tableReady = false;
    if (!tableReady) {
        for (std::uint32_t i = 0; i < 256; ++i) {
            std::uint32_t c = i;
            for (int k = 0; k < 8; ++k) c = (c & 1) ? (0xEDB88320u ^ (c >> 1)) : (c >> 1);
            table[i] = c;
        }
        tableReady = true;
    }
    std::uint32_t crc = 0xFFFFFFFFu;
    const unsigned char* p = static_cast<const unsigned char*>(data);
    while (size--) crc = table[(crc ^ *p++) & 0xFF] ^ (crc >> 8);
    return crc ^ 0xFFFFFFFFu;
}

inline void zipPut16(std::string& out, std::uint16_t v) {
    out.push_back(static_cast<char>(v & 0xFF));
    out.push_back(static_cast<char>((v >> 8) & 0xFF));
}

inline void zipPut32(std::string& out, std::uint32_t v) {
    out.push_back(static_cast<char>(v & 0xFF));
    out.push_back(static_cast<char>((v >> 8) & 0xFF));
    out.push_back(static_cast<char>((v >> 16) & 0xFF));
    out.push_back(static_cast<char>((v >> 24) & 0xFF));
}

inline std::uint16_t zipGet16(const std::string& s, std::size_t off) {
    if (off + 2 > s.size()) return 0;
    return static_cast<std::uint16_t>(static_cast<unsigned char>(s[off])) |
           static_cast<std::uint16_t>(static_cast<unsigned char>(s[off + 1]) << 8);
}

inline std::uint32_t zipGet32(const std::string& s, std::size_t off) {
    if (off + 4 > s.size()) return 0;
    return static_cast<std::uint32_t>(static_cast<unsigned char>(s[off])) |
           static_cast<std::uint32_t>(static_cast<unsigned char>(s[off + 1]) << 8) |
           static_cast<std::uint32_t>(static_cast<unsigned char>(s[off + 2]) << 16) |
           static_cast<std::uint32_t>(static_cast<unsigned char>(s[off + 3]) << 24);
}

// 生成 zip（store 模式）。失败（空条目/名字非法）返回 false。
inline bool makeZip(const std::vector<ZipEntry>& entries, std::string& out) {
    if (entries.empty() || entries.size() > 4096) return false;
    struct CentralRecord {
        std::string name;
        std::uint32_t crc = 0;
        std::uint32_t size = 0;
        std::uint32_t offset = 0;
    };
    std::vector<CentralRecord> centrals;
    centrals.reserve(entries.size());
    out.clear();
    constexpr std::uint16_t kVersion = 20;
    constexpr std::uint16_t kFlags = 0x0800; // UTF-8 名字
    constexpr std::uint16_t kMethod = 0;     // store
    constexpr std::uint16_t kDosTime = 0;    // 1980-00-00 00:00（同步场景不保留 mtime）
    constexpr std::uint16_t kDosDate = 0x21; // 1980-01-01（合法最小值）
    for (const ZipEntry& e : entries) {
        if (e.name.empty() || e.name.size() > 4096) return false;
        if (e.data.size() > 0xFFFFFFFFull) return false;
        // 写入侧同样拒绝绝对路径等非法名字（读取侧另有防护）
        if (e.name.front() == '/' || e.name.front() == '\\' ||
            e.name.find(':') != std::string::npos ||
            e.name.find("..") != std::string::npos) {
            return false;
        }
        CentralRecord central;
        central.name = e.name;
        central.crc = zipCrc32(e.data.data(), e.data.size());
        central.size = static_cast<std::uint32_t>(e.data.size());
        central.offset = static_cast<std::uint32_t>(out.size());
        zipPut32(out, 0x04034b50);
        zipPut16(out, kVersion);
        zipPut16(out, kFlags);
        zipPut16(out, kMethod);
        zipPut16(out, kDosTime);
        zipPut16(out, kDosDate);
        zipPut32(out, central.crc);
        zipPut32(out, central.size);      // compressed（store 模式 = 原大小）
        zipPut32(out, central.size);      // uncompressed
        zipPut16(out, static_cast<std::uint16_t>(e.name.size()));
        zipPut16(out, 0);                 // extra 长度
        out += e.name;
        out += e.data;
        centrals.push_back(std::move(central));
    }
    const std::uint32_t centralOffset = static_cast<std::uint32_t>(out.size());
    for (const CentralRecord& c : centrals) {
        zipPut32(out, 0x02014b50);
        zipPut16(out, kVersion);          // made by
        zipPut16(out, kVersion);          // need
        zipPut16(out, kFlags);
        zipPut16(out, kMethod);
        zipPut16(out, kDosTime);
        zipPut16(out, kDosDate);
        zipPut32(out, c.crc);
        zipPut32(out, c.size);
        zipPut32(out, c.size);
        zipPut16(out, static_cast<std::uint16_t>(c.name.size()));
        zipPut16(out, 0);                 // extra
        zipPut16(out, 0);                 // comment
        zipPut16(out, 0);                 // disk number
        zipPut16(out, 0);                 // internal attrs
        zipPut32(out, 0);                 // external attrs
        zipPut32(out, c.offset);
        out += c.name;
    }
    const std::uint32_t centralSize = static_cast<std::uint32_t>(out.size()) - centralOffset;
    zipPut32(out, 0x06054b50);
    zipPut16(out, 0);
    zipPut16(out, 0);
    zipPut16(out, static_cast<std::uint16_t>(centrals.size()));
    zipPut16(out, static_cast<std::uint16_t>(centrals.size()));
    zipPut32(out, centralSize);
    zipPut32(out, centralOffset);
    zipPut16(out, 0);
    return true;
}

// 读取 zip（仅 store 模式条目）。zip-slip / 超限 / 损坏返回 false。
// 成功时 out 只含文件条目（目录条目名字以 / 结尾，直接跳过）。
inline bool readZip(const std::string& data, std::vector<ZipEntry>& out) {
    out.clear();
    if (data.size() < 22) return false;
    // 定位 EOCD（zip 允许尾部注释，最多回扫 64KB + 22）
    std::size_t eocd = std::string::npos;
    const std::size_t scanFloor = data.size() > 65557 ? data.size() - 65557 : 0;
    for (std::size_t i = data.size() - 22 + 1; i-- > scanFloor;) {
        if (zipGet32(data, i) == 0x06054b50u) { eocd = i; break; }
    }
    if (eocd == std::string::npos) return false;
    const std::uint16_t entryCount = zipGet16(data, eocd + 10);
    std::uint32_t centralOffset = zipGet32(data, eocd + 16);
    if (entryCount == 0 || entryCount > 4096) return false;
    constexpr std::uint64_t kTotalLimit = 32ull << 20; // 32MB
    std::uint64_t totalBytes = 0;
    for (std::uint16_t idx = 0; idx < entryCount; ++idx) {
        if (centralOffset + 46 > data.size() || zipGet32(data, centralOffset) != 0x02014b50u) {
            return false;
        }
        const std::uint16_t method = zipGet16(data, centralOffset + 10);
        const std::uint32_t crc = zipGet32(data, centralOffset + 16);
        const std::uint32_t size = zipGet32(data, centralOffset + 24);
        const std::uint16_t nameLen = zipGet16(data, centralOffset + 28);
        const std::uint16_t extraLen = zipGet16(data, centralOffset + 30);
        const std::uint16_t commentLen = zipGet16(data, centralOffset + 32);
        const std::uint32_t localOffset = zipGet32(data, centralOffset + 42);
        if (centralOffset + 46ull + nameLen + extraLen + commentLen > data.size()) return false;
        const std::string name = data.substr(centralOffset + 46, nameLen);
        centralOffset += 46u + nameLen + extraLen + commentLen;
        if (name.empty() || name.back() == '/') continue; // 目录条目
        // zip-slip 防护：只接受相对路径，且分隔符统一 '/'
        if (name.front() == '/' || name.find(':') != std::string::npos ||
            name.find('\\') != std::string::npos) {
            return false;
        }
        {
            // 逐段检查 ".."
            std::size_t start = 0;
            while (start <= name.size()) {
                std::size_t end = name.find('/', start);
                if (end == std::string::npos) end = name.size();
                const std::string part = name.substr(start, end - start);
                if (part == ".." || part.empty()) return false;
                start = end + 1;
            }
        }
        if (method != 0) return false; // 只支持 store（我们只消费自己产生的包）
        if (localOffset + 30ull > data.size() || zipGet32(data, localOffset) != 0x04034b50u) {
            return false;
        }
        const std::uint16_t localNameLen = zipGet16(data, localOffset + 26);
        const std::uint16_t localExtraLen = zipGet16(data, localOffset + 28);
        const std::uint64_t dataOffset = localOffset + 30ull + localNameLen + localExtraLen;
        if (dataOffset + size > data.size()) return false;
        ZipEntry entry;
        entry.name = name;
        entry.data = data.substr(static_cast<std::size_t>(dataOffset), size);
        if (zipCrc32(entry.data.data(), entry.data.size()) != crc) return false;
        totalBytes += size;
        if (totalBytes > kTotalLimit) return false;
        out.push_back(std::move(entry));
    }
    return !out.empty();
}

} // namespace clipsync

#endif // CLIPSYNC_ZIP_UTIL_H
