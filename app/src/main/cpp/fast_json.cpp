#include "fast_json.h"
#include <cstdlib>
#include <cstring>

namespace lumina {

// 快速找 key 对应的整数值
static int findIntValue(const std::string& s, const char* key) {
    size_t pos = s.find(key);
    if (pos == std::string::npos) return -999;
    pos += strlen(key);
    while (pos < s.size() && (s[pos] == ' ' || s[pos] == ':')) pos++;
    if (pos >= s.size()) return -999;
    return atoi(s.c_str() + pos);
}

// 快速找 key 对应的字符串值
static std::string findStringValue(const std::string& s, const char* key) {
    size_t pos = s.find(key);
    if (pos == std::string::npos) return "";
    pos += strlen(key);
    while (pos < s.size() && (s[pos] == ' ' || s[pos] == ':')) pos++;
    if (pos >= s.size() || s[pos] != '"') return "";
    pos++;
    size_t start = pos;
    while (pos < s.size() && s[pos] != '"') pos++;
    if (pos >= s.size()) return "";
    return s.substr(start, pos - start);
}

LoginResponse parseDrcomResponse(const std::string& body) {
    LoginResponse res;
    res.valid = false;
    res.result = -999;

    // 提取 JSON 部分（去掉 JSONP 包装）
    size_t start = body.find('{');
    size_t end = body.rfind('}');
    if (start == std::string::npos || end == std::string::npos || end <= start) {
        return res;
    }

    std::string json = body.substr(start, end - start + 1);
    res.result = findIntValue(json, "\"result\"");
    if (res.result == -999) return res;

    res.msg = findStringValue(json, "\"msg\"");
    res.valid = true;
    return res;
}

}
