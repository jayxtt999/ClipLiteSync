// zip_util.h 的单元测试（store 模式编解码、CRC 校验、zip-slip 防护，无网络无 UI）

#include "../src/sync/zip_util.h"

#include <cstdio>
#include <string>
#include <vector>

using namespace clipsync;

static int g_failed = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            ++g_failed;                                                   \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);   \
        }                                                                 \
    } while (0)

static void testRoundTrip() {
    std::vector<ZipEntry> entries;
    entries.push_back({"hello.txt", "hello world"});
    entries.push_back({"dir/sub/中文数据.bin", std::string("A\0B", 3)});
    entries.push_back({"empty.dat", ""});
    std::string zip;
    CHECK(makeZip(entries, zip));
    CHECK(zip.size() > 22);
    // PK 头与 EOCD 头
    CHECK(static_cast<unsigned char>(zip[0]) == 0x50 &&
          static_cast<unsigned char>(zip[1]) == 0x4B);
    CHECK(static_cast<unsigned char>(zip[zip.size() - 22]) == 0x50 &&
          static_cast<unsigned char>(zip[zip.size() - 21]) == 0x4B);

    std::vector<ZipEntry> decoded;
    CHECK(readZip(zip, decoded));
    CHECK(decoded.size() == entries.size());
    for (std::size_t i = 0; i < entries.size(); ++i) {
        CHECK(decoded[i].name == entries[i].name);
        CHECK(decoded[i].data == entries[i].data);
    }
}

static void testRejectCorrupt() {
    std::vector<ZipEntry> entries{{"a.txt", "aaa"}};
    std::string zip;
    CHECK(makeZip(entries, zip));
    std::vector<ZipEntry> decoded;
    CHECK(readZip("", decoded) == false);
    CHECK(readZip("PK", decoded) == false);
    // 破坏 EOCD
    std::string badEocd = zip;
    badEocd[badEocd.size() - 4] = 0x99;
    CHECK(readZip(badEocd, decoded) == false);
    // 篡改条目数据（CRC 不匹配）
    std::string badCrc = zip;
    badCrc[40] ^= 0xFF;
    CHECK(readZip(badCrc, decoded) == false);
    // 空条目
    CHECK(makeZip({}, zip) == false);
}

static void testRejectZipSlip() {
    std::vector<ZipEntry> decoded;
    // 手工构造非法名字的条目交给 makeZip —— 写入侧就应拒绝
    std::string zip;
    CHECK(makeZip({{"../evil.txt", "x"}}, zip) == false);
    CHECK(makeZip({{"/abs.txt", "x"}}, zip) == false);
    CHECK(makeZip({{"C:\\evil.txt", "x"}}, zip) == false);

    // 绕过写入侧、直接构造含恶意 central directory 的包（读侧必须拦截）
    auto put16 = [](std::string& s, std::uint16_t v) {
        s.push_back(static_cast<char>(v & 0xFF));
        s.push_back(static_cast<char>((v >> 8) & 0xFF));
    };
    auto put32 = [](std::string& s, std::uint32_t v) {
        s.push_back(static_cast<char>(v & 0xFF));
        s.push_back(static_cast<char>((v >> 8) & 0xFF));
        s.push_back(static_cast<char>((v >> 16) & 0xFF));
        s.push_back(static_cast<char>((v >> 24) & 0xFF));
    };
    const std::string data = "x";
    const std::uint32_t crc = zipCrc32(data.data(), data.size());
    const char* evilNames[] = {"../evil.txt", "a\\b.txt", "..", "C:evil"};
    for (const char* evilName : evilNames) {
        std::string crafted;
        const std::uint32_t localOffset = 0;
        put32(crafted, 0x04034b50);   // local header
        put16(crafted, 20);           // version
        put16(crafted, 0);            // flags
        put16(crafted, 0);            // method store
        put16(crafted, 0);            // time
        put16(crafted, 0x21);         // date
        put32(crafted, crc);
        put32(crafted, static_cast<std::uint32_t>(data.size()));
        put32(crafted, static_cast<std::uint32_t>(data.size()));
        put16(crafted, static_cast<std::uint16_t>(std::string(evilName).size()));
        put16(crafted, 0);
        crafted += evilName;
        crafted += data;
        const std::uint32_t centralOffset = static_cast<std::uint32_t>(crafted.size());
        put32(crafted, 0x02014b50);
        put16(crafted, 20);
        put16(crafted, 20);
        put16(crafted, 0);
        put16(crafted, 0);
        put16(crafted, 0);
        put16(crafted, 0x21);
        put32(crafted, crc);
        put32(crafted, static_cast<std::uint32_t>(data.size()));
        put32(crafted, static_cast<std::uint32_t>(data.size()));
        put16(crafted, static_cast<std::uint16_t>(std::string(evilName).size()));
        put16(crafted, 0);
        put16(crafted, 0);
        put16(crafted, 0);
        put16(crafted, 0);
        put32(crafted, 0);
        put32(crafted, localOffset);
        crafted += evilName;
        const std::uint32_t centralSize = static_cast<std::uint32_t>(crafted.size()) - centralOffset;
        put32(crafted, 0x06054b50);
        put16(crafted, 0);
        put16(crafted, 0);
        put16(crafted, 1);
        put16(crafted, 1);
        put32(crafted, centralSize);
        put32(crafted, centralOffset);
        put16(crafted, 0);
        CHECK(readZip(crafted, decoded) == false);
    }
}

static void testDuplicateNamesPreserved() {
    std::vector<ZipEntry> entries{
        {"a.txt", "first"},
        {"a.txt", "second"},
    };
    std::string zip;
    CHECK(makeZip(entries, zip)); // 重名由调用方（main.cpp）处理，zip 层原样写入
    std::vector<ZipEntry> decoded;
    CHECK(readZip(zip, decoded));
    CHECK(decoded.size() == 2);
    CHECK(decoded[0].data == "first");
    CHECK(decoded[1].data == "second");
}

int main() {
    testRoundTrip();
    testRejectCorrupt();
    testRejectZipSlip();
    testDuplicateNamesPreserved();
    if (g_failed == 0) {
        std::printf("all zip util tests passed\n");
        return 0;
    }
    std::printf("%d check(s) failed\n", g_failed);
    return 1;
}
