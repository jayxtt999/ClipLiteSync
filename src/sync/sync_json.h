#ifndef CLIPSYNC_SYNC_JSON_H
#define CLIPSYNC_SYNC_JSON_H

// 最小 JSON 解析与转义（协议帧体积小、结构固定，够用即可）。
// 支持：object / array / string / number(int|double) / true / false / null。
// 字符串按 UTF-8 原样存储；\uXXXX 解码为 UTF-8。

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace clipsync {

class JValue {
public:
    enum class Type { Null, Bool, Number, String, Array, Object };

    Type type = Type::Null;
    bool b = false;
    double num = 0;
    std::string str;
    std::vector<JValue> arr;
    std::map<std::string, JValue> obj;

    bool isNull() const { return type == Type::Null; }
    bool isObject() const { return type == Type::Object; }
    bool isArray() const { return type == Type::Array; }

    bool has(const std::string& key) const { return type == Type::Object && obj.count(key) != 0; }
    const JValue& at(const std::string& key) const {
        static const JValue nullValue;
        auto it = obj.find(key);
        return it == obj.end() ? nullValue : it->second;
    }
    std::int64_t asInt(std::int64_t def = 0) const {
        return type == Type::Number ? static_cast<std::int64_t>(num) : def;
    }
    std::string asString(const std::string& def = {}) const {
        return type == Type::String ? str : def;
    }
    bool asBool(bool def = false) const {
        return type == Type::Bool ? b : def;
    }
};

class JsonParser {
public:
    static bool parse(const std::string& text, JValue& out) {
        JsonParser p(text);
        p.skipWs();
        if (!p.parseValue(out, 0)) return false;
        p.skipWs();
        return p.pos_ >= p.text_.size();
    }

private:
    explicit JsonParser(const std::string& text) : text_(text) {}

    const std::string& text_;
    std::size_t pos_ = 0;
    static constexpr int kMaxDepth = 32;

    void skipWs() {
        while (pos_ < text_.size()) {
            char c = text_[pos_];
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') ++pos_;
            else break;
        }
    }

    bool parseValue(JValue& out, int depth) {
        if (depth > kMaxDepth || pos_ >= text_.size()) return false;
        char c = text_[pos_];
        switch (c) {
        case '{': return parseObject(out, depth);
        case '[': return parseArray(out, depth);
        case '"': out.type = JValue::Type::String; return parseString(out.str);
        case 't':
            if (text_.compare(pos_, 4, "true") == 0) { pos_ += 4; out.type = JValue::Type::Bool; out.b = true; return true; }
            return false;
        case 'f':
            if (text_.compare(pos_, 5, "false") == 0) { pos_ += 5; out.type = JValue::Type::Bool; out.b = false; return true; }
            return false;
        case 'n':
            if (text_.compare(pos_, 4, "null") == 0) { pos_ += 4; out.type = JValue::Type::Null; return true; }
            return false;
        default: return parseNumber(out);
        }
    }

    bool parseObject(JValue& out, int depth) {
        out.type = JValue::Type::Object;
        ++pos_; // {
        skipWs();
        if (pos_ < text_.size() && text_[pos_] == '}') { ++pos_; return true; }
        while (pos_ < text_.size()) {
            skipWs();
            if (text_[pos_] != '"') return false;
            std::string key;
            if (!parseString(key)) return false;
            skipWs();
            if (pos_ >= text_.size() || text_[pos_] != ':') return false;
            ++pos_;
            skipWs();
            JValue value;
            if (!parseValue(value, depth + 1)) return false;
            out.obj[key] = std::move(value);
            skipWs();
            if (pos_ < text_.size() && text_[pos_] == ',') { ++pos_; continue; }
            if (pos_ < text_.size() && text_[pos_] == '}') { ++pos_; return true; }
            return false;
        }
        return false;
    }

    bool parseArray(JValue& out, int depth) {
        out.type = JValue::Type::Array;
        ++pos_; // [
        skipWs();
        if (pos_ < text_.size() && text_[pos_] == ']') { ++pos_; return true; }
        while (pos_ < text_.size()) {
            skipWs();
            JValue value;
            if (!parseValue(value, depth + 1)) return false;
            out.arr.push_back(std::move(value));
            skipWs();
            if (pos_ < text_.size() && text_[pos_] == ',') { ++pos_; continue; }
            if (pos_ < text_.size() && text_[pos_] == ']') { ++pos_; return true; }
            return false;
        }
        return false;
    }

    bool parseString(std::string& out) {
        ++pos_; // "
        out.clear();
        while (pos_ < text_.size()) {
            char c = text_[pos_];
            if (c == '"') { ++pos_; return true; }
            if (c == '\\') {
                ++pos_;
                if (pos_ >= text_.size()) return false;
                char e = text_[pos_++];
                switch (e) {
                case '"': out += '"'; break;
                case '\\': out += '\\'; break;
                case '/': out += '/'; break;
                case 'b': out += '\b'; break;
                case 'f': out += '\f'; break;
                case 'n': out += '\n'; break;
                case 'r': out += '\r'; break;
                case 't': out += '\t'; break;
                case 'u': {
                    unsigned code = 0;
                    if (!parseHex4(code)) return false;
                    appendUtf8(out, code);
                    break;
                }
                default: return false;
                }
                continue;
            }
            out += c;
            ++pos_;
        }
        return false;
    }

    bool parseHex4(unsigned& out) {
        if (pos_ + 4 > text_.size()) return false;
        out = 0;
        for (int i = 0; i < 4; ++i) {
            char c = text_[pos_++];
            out <<= 4;
            if (c >= '0' && c <= '9') out |= static_cast<unsigned>(c - '0');
            else if (c >= 'a' && c <= 'f') out |= static_cast<unsigned>(c - 'a' + 10);
            else if (c >= 'A' && c <= 'F') out |= static_cast<unsigned>(c - 'A' + 10);
            else return false;
        }
        return true;
    }

    static void appendUtf8(std::string& out, unsigned code) {
        if (code < 0x80) {
            out += static_cast<char>(code);
        } else if (code < 0x800) {
            out += static_cast<char>(0xC0 | (code >> 6));
            out += static_cast<char>(0x80 | (code & 0x3F));
        } else {
            out += static_cast<char>(0xE0 | (code >> 12));
            out += static_cast<char>(0x80 | ((code >> 6) & 0x3F));
            out += static_cast<char>(0x80 | (code & 0x3F));
        }
    }

    bool parseNumber(JValue& out) {
        std::size_t start = pos_;
        if (pos_ < text_.size() && (text_[pos_] == '-' || text_[pos_] == '+')) ++pos_;
        bool hasDigit = false;
        while (pos_ < text_.size()) {
            char c = text_[pos_];
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                if (c >= '0' && c <= '9') hasDigit = true;
                ++pos_;
            } else break;
        }
        if (!hasDigit) return false;
        out.type = JValue::Type::Number;
        out.num = std::stod(text_.substr(start, pos_ - start));
        return true;
    }
};

inline bool parseJson(const std::string& text, JValue& out) {
    return JsonParser::parse(text, out);
}

// 序列化辅助：字符串转义
inline std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                char buf[8];
                sprintf_s(buf, sizeof(buf), "\\u%04x", c);
                out += buf;
            } else {
                out += static_cast<char>(c);
            }
        }
    }
    return out;
}

} // namespace clipsync

#endif // CLIPSYNC_SYNC_JSON_H
