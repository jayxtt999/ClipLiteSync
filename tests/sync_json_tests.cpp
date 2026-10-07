// sync_json.h / sync_util.h 的单元测试（无网络、无 UI，CI 可跑）

#include "../src/sync/sync_json.h"
#include "../src/sync/sync_util.h"

#include <cstdio>
#include <string>

using namespace clipsync;

static int g_failed = 0;

#define CHECK(cond)                                                       \
    do {                                                                  \
        if (!(cond)) {                                                    \
            ++g_failed;                                                   \
            std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);   \
        }                                                                 \
    } while (0)

static void testParseObject() {
    JValue v;
    CHECK(parseJson("{\"v\":1,\"type\":\"hello.ok\",\"seq\":123,\"replayed\":4}", v));
    CHECK(v.isObject());
    CHECK(v.at("v").asInt() == 1);
    CHECK(v.at("type").asString() == "hello.ok");
    CHECK(v.at("seq").asInt() == 123);
    CHECK(v.at("replayed").asInt() == 4);
    CHECK(v.at("missing").isNull());
}

static void testParseEventFrame() {
    std::string text =
        "{\"v\":1,\"type\":\"event\",\"seq\":7,\"byDevice\":\"dev-abc\",\"event\":"
        "{\"kind\":\"clip.add\",\"syncId\":\"s-1\",\"clipType\":1,\"hash\":9223372036854775807,"
        "\"localOnly\":false,\"sourceApp\":\"chrome.exe\",\"ts\":1728283021123,"
        "\"payloadB64\":\"aGVsbG8=\"}}";
    JValue f;
    CHECK(parseJson(text, f));
    const JValue& ev = f.at("event");
    CHECK(ev.isObject());
    CHECK(ev.at("kind").asString() == "clip.add");
    CHECK(ev.at("hash").asInt() == 9223372036854775807LL);
    CHECK(ev.at("ts").asInt() == 1728283021123LL);
    CHECK(ev.at("localOnly").asBool() == false);
    std::string payload;
    CHECK(base64Decode(ev.at("payloadB64").asString(), payload));
    CHECK(payload == "hello");
}

static void testParseUnicodeAndEscapes() {
    JValue v;
    CHECK(parseJson("{\"s\":\"a\\nb\\u4e2d\\u6587\\\"q\\\"\"}", v));
    CHECK(v.at("s").asString() == std::string("a\nb\xe4\xb8\xad\xe6\x96\x87\"q\""));
}

static void testParseArrayAndErrors() {
    JValue v;
    CHECK(parseJson("[1,2,3,{\"k\":true},null]", v));
    CHECK(v.isArray() && v.arr.size() == 5);
    CHECK(v.arr[3].at("k").asBool() == true);
    CHECK(!parseJson("{bad", v));
    CHECK(!parseJson("{\"a\":}", v));
    CHECK(!parseJson("", v));
}

static void testEscapeRoundTrip() {
    std::string raw = "he\"llo\n\\x\t";
    std::string escaped = jsonEscape(raw);
    std::string jsonText = "{\"s\":\"" + escaped + "\"}";
    JValue v;
    CHECK(parseJson(jsonText, v));
    CHECK(v.at("s").asString() == raw);
}

static void testBase64() {
    CHECK(base64Encode("hello") == "aGVsbG8=");
    CHECK(base64Encode("hellos") == "aGVsbG9z");
    std::string out;
    CHECK(base64Decode("aGVsbG8=", out) && out == "hello");
    CHECK(base64Decode("aGVs\nbG8=", out) && out == "hello"); // 允许换行
    std::string binary;
    binary += static_cast<char>(0) + std::string("\x01\xff\xfe", 3);
    std::string enc = base64Encode(binary);
    std::string dec;
    CHECK(base64Decode(enc, dec) && dec == binary);
}

static void testSyncIdUnique() {
    std::string a = makeSyncId();
    std::string b = makeSyncId();
    CHECK(!a.empty() && !b.empty() && a != b);
}

int main() {
    testParseObject();
    testParseEventFrame();
    testParseUnicodeAndEscapes();
    testParseArrayAndErrors();
    testEscapeRoundTrip();
    testBase64();
    testSyncIdUnique();
    if (g_failed == 0) {
        std::printf("all sync json tests passed\n");
        return 0;
    }
    std::printf("%d check(s) failed\n", g_failed);
    return 1;
}
