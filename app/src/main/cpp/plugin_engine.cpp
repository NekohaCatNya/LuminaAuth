#include "plugin_engine.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <map>
#include <mutex>
#include <optional>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include "native_http.h"

namespace lumina {
namespace {

// ---------------------------------------------------------------------------
// 小型 YAML 子集解析器：只服务插件文件，不引入第三方 YAML 依赖。
// 支持嵌套 map/sequence、单双引号、整数/布尔/null、注释，以及 request: |
// 的原始 HTTP 块（块内保留空行与 #）。
// ---------------------------------------------------------------------------

struct YValue {
    enum class Type { Null, Str, Int, Bool, Map, List };

    Type type = Type::Null;
    std::string str;
    long long integer = 0;
    bool boolean = false;
    std::vector<std::pair<std::string, YValue>> map;
    std::vector<YValue> list;

    static YValue makeNull() { return YValue{}; }
    static YValue makeStr(std::string v) {
        YValue x; x.type = Type::Str; x.str = std::move(v); return x;
    }
    static YValue makeInt(long long v) {
        YValue x; x.type = Type::Int; x.integer = v; return x;
    }
    static YValue makeBool(bool v) {
        YValue x; x.type = Type::Bool; x.boolean = v; return x;
    }
    static YValue makeMap() { YValue x; x.type = Type::Map; return x; }
    static YValue makeList() { YValue x; x.type = Type::List; return x; }

    const YValue* get(const std::string& key) const {
        if (type != Type::Map) return nullptr;
        for (auto& kv : map) if (kv.first == key) return &kv.second;
        return nullptr;
    }
};

struct PluginError : std::runtime_error {
    explicit PluginError(const std::string& msg) : std::runtime_error(msg) {}
};

static std::string trim(const std::string& s) {
    size_t a = 0, b = s.size();
    while (a < b && std::isspace((unsigned char)s[a])) ++a;
    while (b > a && std::isspace((unsigned char)s[b - 1])) --b;
    return s.substr(a, b - a);
}

static bool startsWith(const std::string& s, const std::string& p) {
    return s.size() >= p.size() && s.compare(0, p.size(), p) == 0;
}

static std::string lowerAscii(std::string s) {
    for (auto& c : s) c = (char)std::tolower((unsigned char)c);
    return s;
}

static bool iequals(const std::string& a, const std::string& b) {
    return lowerAscii(a) == lowerAscii(b);
}

// 清掉聊天工具粘贴时混入的不可见字符；制表符保留，由 YAML 解析阶段拒绝。
static std::string cleanHiddenChars(const std::string& in) {
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size();) {
        unsigned char c = (unsigned char)in[i];
        if (c < 0x80) {
            if ((c >= 0x00 && c <= 0x08) || c == 0x0B || c == 0x0C || (c >= 0x0E && c <= 0x1F) || c == 0x7F) {
                ++i;
                continue;
            }
            out.push_back((char)c);
            ++i;
            continue;
        }
        // 解码 UTF-8，够用即可；非法序列原样保留。
        unsigned cp = 0;
        size_t len = 0;
        if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        if (len == 0 || i + len > in.size()) {
            out.push_back((char)c);
            ++i;
            continue;
        }
        bool valid = true;
        for (size_t k = 1; k < len; ++k) {
            unsigned char cc = (unsigned char)in[i + k];
            if ((cc & 0xC0) != 0x80) { valid = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!valid) {
            out.push_back((char)c);
            ++i;
            continue;
        }
        // 零宽/软连字符/控制类字符丢弃；全角空格转半角；行分隔符转换行。
        if ((cp >= 0x200B && cp <= 0x200F) || cp == 0x2060 || cp == 0xFEFF ||
            cp == 0x180E || cp == 0x00AD) {
            i += len;
            continue;
        }
        if (cp == 0x3000) {
            out.push_back(' ');
            i += len;
            continue;
        }
        if (cp == 0x2028 || cp == 0x2029) {
            out.push_back('\n');
            i += len;
            continue;
        }
        out.append(in, i, len);
        i += len;
    }
    return out;
}

static std::string stripComment(const std::string& s) {
    char quote = 0;
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if (quote) {
            if (c == quote) quote = 0;
        } else if (c == '"' || c == '\'') {
            quote = c;
        } else if (c == '#') {
            return s.substr(0, i);
        }
    }
    return s;
}

static int findKeyColon(const std::string& s) {
    char quote = 0;
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s[i];
        if (quote) {
            if (c == quote) quote = 0;
        } else if (c == '"' || c == '\'') {
            quote = c;
        } else if (c == ':' && (i + 1 == s.size() || s[i + 1] == ' ')) {
            return (int)i;
        }
    }
    return -1;
}

static std::string unquote(const std::string& raw) {
    std::string t = trim(raw);
    if (t.size() >= 2 && t.front() == '"' && t.back() == '"') {
        std::string out;
        for (size_t i = 1; i + 1 < t.size(); ++i) {
            char c = t[i];
            if (c == '\\' && i + 2 < t.size()) {
                char n = t[++i];
                switch (n) {
                    case 'n': out.push_back('\n'); break;
                    case 't': out.push_back('\t'); break;
                    case 'r': out.push_back('\r'); break;
                    case '\\': out.push_back('\\'); break;
                    case '"': out.push_back('"'); break;
                    default: out.push_back(n); break;
                }
            } else {
                out.push_back(c);
            }
        }
        return out;
    }
    if (t.size() >= 2 && t.front() == '\'' && t.back() == '\'') {
        std::string out = t.substr(1, t.size() - 2);
        std::string result;
        for (size_t i = 0; i < out.size(); ++i) {
            if (out[i] == '\'' && i + 1 < out.size() && out[i + 1] == '\'') { result.push_back('\''); ++i; }
            else result.push_back(out[i]);
        }
        return result;
    }
    return t;
}

static YValue parseScalar(const std::string& raw) {
    std::string s = trim(raw);
    if (s.empty()) return YValue::makeNull();
    if (s.front() == '"' || s.front() == '\'') return YValue::makeStr(unquote(s));
    if (s == "true" || s == "True" || s == "TRUE") return YValue::makeBool(true);
    if (s == "false" || s == "False" || s == "FALSE") return YValue::makeBool(false);
    if (s == "null" || s == "Null" || s == "~") return YValue::makeNull();
    char* end = nullptr;
    long long n = std::strtoll(s.c_str(), &end, 10);
    if (end && *end == '\0') return YValue::makeInt(n);
    return YValue::makeStr(s);
}

struct YamlLine {
    int indent = 0;
    std::string text;   // 去注释后的内容
    std::string raw;    // 原始行（raw block 保留 # 与空行）
    int number = 0;
    bool blank = true;
};

static YValue parseYaml(const std::string& input);

struct YamlParser {
    std::vector<YamlLine> lines;

    explicit YamlParser(const std::string& input) {
        std::string cleaned = cleanHiddenChars(input);
        std::istringstream ss(cleaned);
        std::string raw;
        int no = 0;
        while (std::getline(ss, raw)) {
            ++no;
            if (!raw.empty() && raw.back() == '\r') raw.pop_back();
            if (raw.find('\t') != std::string::npos) {
                throw PluginError("第 " + std::to_string(no) + " 行不允许使用 Tab 缩进，请改用空格");
            }
            std::string noComment = stripComment(raw);
            YamlLine l;
            l.raw = raw;
            l.number = no;
            l.text = noComment;
            if (noComment.empty() || trim(noComment).empty()) {
                l.blank = true;
                l.indent = 0;
                lines.push_back(std::move(l));
                continue;
            }
            int ind = 0;
            while (ind < (int)noComment.size() && noComment[ind] == ' ') ++ind;
            l.indent = ind;
            l.text = noComment.substr(ind);
            l.text = l.text.substr(0, l.text.find_last_not_of(" \t") + 1);
            l.blank = l.text.empty();
            lines.push_back(std::move(l));
        }
    }

    YValue parse() {
        if (lines.empty()) return YValue::makeNull();
        size_t start = nextNonBlank(0);
        if (start >= lines.size()) return YValue::makeNull();
        return parseBlock(start, lines[start].indent, nullptr);
    }

    size_t nextNonBlank(size_t i) const {
        while (i < lines.size() && lines[i].blank) ++i;
        return i;
    }

    YValue parseBlock(size_t start, int indent, size_t* consumed = nullptr) {
        size_t i = nextNonBlank(start);
        if (i >= lines.size() || lines[i].indent < indent) {
            if (consumed) *consumed = i;
            return YValue::makeNull();
        }
        size_t next = i;
        YValue v = (lines[i].text == "-" || startsWith(lines[i].text, "- "))
            ? parseSequence(i, indent, &next) : parseMap(i, indent, &next);
        if (consumed) *consumed = next;
        return v;
    }

    YValue parseMap(size_t start, int indent, size_t* consumed = nullptr) {
        YValue map = YValue::makeMap();
        size_t i = start;
        while (i < lines.size()) {
            if (lines[i].blank) { ++i; continue; }
            if (lines[i].indent < indent) break;
            if (lines[i].indent > indent) {
                throw PluginError("第 " + std::to_string(lines[i].number) + " 行缩进异常（本层应为 " +
                                  std::to_string(indent) + " 个空格）");
            }
            if (lines[i].text == "-" || startsWith(lines[i].text, "- ")) {
                throw PluginError("第 " + std::to_string(lines[i].number) + " 行出现多余的序列项");
            }
            int colon = findKeyColon(lines[i].text);
            if (colon < 0) {
                throw PluginError("第 " + std::to_string(lines[i].number) + " 行缺少键值冒号，应写成 key: value");
            }
            std::string key = unquote(lines[i].text.substr(0, colon));
            if (!key.empty()) {
                for (auto& kv : map.map) {
                    if (kv.first == key) throw PluginError("第 " + std::to_string(lines[i].number) + " 行出现重复键 " + key);
                }
            }
            std::string rest = trim(lines[i].text.substr(colon + 1));
            if (rest.size() >= 1 && rest[0] == '|' && (rest.size() == 1 || rest[1] == '-' || rest[1] == '+')) {
                // 原始块：保留缩进后的每一行，空行也保留；遇到下一层键停止。
                std::vector<std::string> block;
                size_t j = i + 1;
                int minIndent = -1;
                for (; j < lines.size(); ++j) {
                    if (!lines[j].blank) {
                        if (lines[j].indent <= indent) break;
                        if (minIndent < 0 || lines[j].indent < minIndent) minIndent = lines[j].indent;
                    }
                }
                // 重新扫描，收集到结束位置；blank 行也保留（删掉末尾多余空行）。
                j = i + 1;
                for (; j < lines.size(); ++j) {
                    if (!lines[j].blank && lines[j].indent <= indent) break;
                    if (lines[j].blank) {
                        block.push_back("");
                    } else {
                        std::string raw = lines[j].raw;
                        int strip = minIndent < 0 ? lines[j].indent : minIndent;
                        if (strip > 0 && strip <= (int)raw.size()) raw = raw.substr(strip);
                        block.push_back(raw);
                    }
                }
                while (!block.empty() && block.back().empty()) block.pop_back();
                std::ostringstream os;
                for (size_t k = 0; k < block.size(); ++k) { if (k) os << '\n'; os << block[k]; }
                map.map.emplace_back(key, YValue::makeStr(os.str()));
                i = j;
                continue;
            }
            if (!rest.empty()) {
                map.map.emplace_back(key, parseScalar(rest));
                ++i;
                continue;
            }
            // 空值：下一层缩进更深则递归，否则 null。
            size_t next = nextNonBlank(i + 1);
            if (next < lines.size() && lines[next].indent > indent) {
                size_t consumedNext = next;
                YValue child = parseBlock(next, lines[next].indent, &consumedNext);
                map.map.emplace_back(key, std::move(child));
                i = consumedNext;
            } else {
                map.map.emplace_back(key, YValue::makeNull());
                ++i;
            }
        }
        if (consumed) *consumed = i;
        return map;
    }

    YValue parseSequence(size_t start, int indent, size_t* consumed = nullptr) {
        YValue list = YValue::makeList();
        size_t i = start;
        while (i < lines.size()) {
            if (lines[i].blank) { ++i; continue; }
            if (lines[i].indent != indent) break;
            if (!(lines[i].text == "-" || startsWith(lines[i].text, "- "))) break;
            std::string rest = lines[i].text == "-" ? "" : lines[i].text.substr(2);
            if (trim(rest).empty()) {
                size_t next = nextNonBlank(i + 1);
                if (next < lines.size() && lines[next].indent > indent) {
                    size_t consumedChild = next;
                    list.list.push_back(parseBlock(next, lines[next].indent, &consumedChild));
                    i = consumedChild;
                } else {
                    list.list.push_back(YValue::makeNull());
                    ++i;
                }
                continue;
            }
            if (findKeyColon(rest) >= 0) {
                // "- id: x" 后面可能跟着同项的其他键，构造虚拟 map。
                YValue item = YValue::makeMap();
                size_t j = i;
                int itemIndent = indent + 2;
                // 第一行 remainder 当作 map 的第一项。
                // 为避免修改 lines，专用的 parseInlineMapFirst。
                parseInlineMapFirst(item, rest, itemIndent, i, &j);
                i = j;
                list.list.push_back(std::move(item));
            } else {
                list.list.push_back(parseScalar(rest));
                ++i;
            }
        }
        if (consumed) *consumed = i;
        return list;
    }

    // 处理 "- key: value" 以及后续缩进更深/等于 itemIndent 的键。
    void parseInlineMapFirst(YValue& map, const std::string& first, int itemIndent, size_t lineIndex, size_t* next) {
        (void)lineIndex;
        size_t i = lineIndex + 1;
        // 先把第一个键放入 map。
        int c = findKeyColon(first);
        std::string key = unquote(first.substr(0, c));
        std::string rest = trim(first.substr(c + 1));
        if (!rest.empty() && (rest == "|" || rest == "|-" || rest == "|+")) {
            // 序列项首个键就是原始 HTTP 块（例如 "- request: |"）。
            std::vector<std::string> block;
            size_t j = i;
            int minIndent = -1;
            for (; j < lines.size(); ++j) {
                if (!lines[j].blank) {
                    if (lines[j].indent <= itemIndent) break;
                    if (minIndent < 0 || lines[j].indent < minIndent) minIndent = lines[j].indent;
                }
            }
            j = i;
            for (; j < lines.size() && (lines[j].blank || lines[j].indent > itemIndent); ++j) {
                if (lines[j].blank) block.push_back("");
                else {
                    std::string raw = lines[j].raw;
                    int strip = minIndent < 0 ? lines[j].indent : minIndent;
                    if (strip > 0 && strip <= (int)raw.size()) raw = raw.substr(strip);
                    block.push_back(raw);
                }
            }
            while (!block.empty() && block.back().empty()) block.pop_back();
            std::ostringstream os;
            for (size_t k = 0; k < block.size(); ++k) { if (k) os << '\n'; os << block[k]; }
            map.map.emplace_back(key, YValue::makeStr(os.str()));
            i = j;
        } else if (!rest.empty()) {
            map.map.emplace_back(key, parseScalar(rest));
        } else {
            size_t n = nextNonBlank(i);
            if (n < lines.size() && lines[n].indent > itemIndent) {
                size_t dummy = n;
                YValue child = parseBlock(n, lines[n].indent, &dummy);
                map.map.emplace_back(key, std::move(child));
                while (i < lines.size() && (lines[i].blank || lines[i].indent > itemIndent)) ++i;
            } else {
                map.map.emplace_back(key, YValue::makeNull());
            }
        }
        // 后续同项键：缩进必须等于 itemIndent（或更深由 parseMap 处理）。
        while (i < lines.size()) {
            if (lines[i].blank) { ++i; continue; }
            if (lines[i].indent != itemIndent) break;
            if (lines[i].text == "-" || startsWith(lines[i].text, "- ")) break;
            int colon = findKeyColon(lines[i].text);
            if (colon < 0) throw PluginError("第 " + std::to_string(lines[i].number) + " 行缺少键值冒号");
            std::string k = unquote(lines[i].text.substr(0, colon));
            std::string r = trim(lines[i].text.substr(colon + 1));
            if (r.size() >= 1 && r[0] == '|') {
                // 序列项里的原始块：复用与 map 相同的收集逻辑，但把首行视为已消费。
                std::vector<std::string> block;
                size_t j = i + 1;
                int minIndent = -1;
                for (; j < lines.size(); ++j) {
                    if (!lines[j].blank) {
                        if (lines[j].indent <= itemIndent) break;
                        if (minIndent < 0 || lines[j].indent < minIndent) minIndent = lines[j].indent;
                    }
                }
                j = i + 1;
                for (; j < lines.size() && (lines[j].blank || lines[j].indent > itemIndent); ++j) {
                    if (lines[j].blank) block.push_back("");
                    else {
                        std::string raw = lines[j].raw;
                        int strip = minIndent < 0 ? lines[j].indent : minIndent;
                        if (strip > 0 && strip <= (int)raw.size()) raw = raw.substr(strip);
                        block.push_back(raw);
                    }
                }
                while (!block.empty() && block.back().empty()) block.pop_back();
                std::ostringstream os;
                for (size_t k = 0; k < block.size(); ++k) { if (k) os << '\n'; os << block[k]; }
                map.map.emplace_back(k, YValue::makeStr(os.str()));
                i = j;
                continue;
            }
            if (!r.empty()) {
                map.map.emplace_back(k, parseScalar(r));
                ++i;
            } else {
                size_t n = nextNonBlank(i + 1);
                if (n < lines.size() && lines[n].indent > itemIndent) {
                    size_t dummy = n;
                    YValue child = parseBlock(n, lines[n].indent, &dummy);
                    map.map.emplace_back(k, std::move(child));
                    i = dummy;
                } else {
                    map.map.emplace_back(k, YValue::makeNull());
                    ++i;
                }
            }
        }
        if (next) *next = i;
    }
};

static YValue parseYaml(const std::string& input) {
    YamlParser parser(input);
    return parser.parse();
}

// ---------------------------------------------------------------------------
// 数据结构
// ---------------------------------------------------------------------------

struct Header { std::string name, value; };
using Headers = std::vector<Header>;

struct MatchDef {
    bool present = false;
    std::optional<std::string> bodyContains, bodyRegex, jsonPath, jsonEquals;
};

struct CaptureDef {
    std::string name;
    std::optional<std::string> jsonPath, regex;
    int group = 1;
};

struct CheckDef {
    bool present = false;
    std::string method = "GET";
    std::string url;
    std::optional<std::string> onlineContains, offlineContains;
    Headers headers;
    std::optional<std::string> body;
};

struct StepDef {
    std::string name;
    std::string method = "GET";
    std::string url;
    std::optional<int> expectStatus;
    MatchDef success, alreadyOnline;
    std::vector<CaptureDef> captures;
    std::optional<std::string> failJsonPath;
    Headers headers;
    std::optional<std::string> body;
};

struct FieldDef { std::string id, label, type; bool required = true; };
struct IspDef { std::string id, label; bool isDefault = false; };

struct TimingDef {
    long long ipPollMs = 250;
    long long maxIpWaitMs = 3000;
    bool skipNetworkReadyOnNotify = true;
    int connectTimeoutMs = 6000;
    int readTimeoutMs = 6000;
};

struct PolicyDef {
    bool bindProcess = true, promoteNetwork = true, rootSetDefault = true, authOverWifi = true;
    std::vector<std::string> preferredIfaces;
};

struct DetectDef { int retryCount = 3; long long retryDelayMs = 5000; };

struct Plugin {
    std::string id, name, description;
    int version = 1;
    std::vector<std::string> ssids;
    std::vector<std::string> hosts; // lower-case authority
    std::vector<FieldDef> fields;
    std::vector<IspDef> isps;
    CheckDef check;
    std::vector<StepDef> steps;
    Headers headers;
    TimingDef timing;
    PolicyDef policy;
    DetectDef detect;
};

// ---------------------------------------------------------------------------
// JSON 输出
// ---------------------------------------------------------------------------

static std::string jsonStr(const std::string& s) {
    std::string out = "\"";
    for (char cc : s) {
        unsigned char c = (unsigned char)cc;
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else out += (char)c;
        }
    }
    out += '"';
    return out;
}

static std::string jsonOpt(const std::optional<std::string>& v) { return v ? jsonStr(*v) : "null"; }
static std::string jsonHeaders(const Headers& h) {
    std::string out = "{";
    for (size_t i = 0; i < h.size(); ++i) {
        if (i) out += ",";
        out += jsonStr(h[i].name) + ":" + jsonStr(h[i].value);
    }
    out += "}";
    return out;
}

static std::string matchJson(const MatchDef& m) {
    if (!m.present) return "null";
    return "{\"contains\":" + jsonOpt(m.bodyContains) +
           ",\"regex\":" + jsonOpt(m.bodyRegex) +
           ",\"jsonPath\":" + jsonOpt(m.jsonPath) +
           ",\"jsonEquals\":" + jsonOpt(m.jsonEquals) + "}";
}

static std::string pluginJson(const Plugin& p) {
    std::ostringstream os;
    os << "{";
    os << "\"id\":" << jsonStr(p.id) << ",\"name\":" << jsonStr(p.name)
       << ",\"version\":" << p.version << ",\"description\":" << jsonStr(p.description);
    os << ",\"ssids\":[";
    for (size_t i = 0; i < p.ssids.size(); ++i) { if (i) os << ","; os << jsonStr(p.ssids[i]); }
    os << "]";
    os << ",\"hosts\":[";
    for (size_t i = 0; i < p.hosts.size(); ++i) { if (i) os << ","; os << jsonStr(p.hosts[i]); }
    os << "]";
    os << ",\"fields\":[";
    for (size_t i = 0; i < p.fields.size(); ++i) {
        if (i) os << ",";
        const auto& f = p.fields[i];
        os << "{\"id\":" << jsonStr(f.id) << ",\"label\":" << jsonStr(f.label)
           << ",\"type\":" << jsonStr(f.type) << ",\"required\":" << (f.required ? "true" : "false") << "}";
    }
    os << "]";
    os << ",\"isps\":[";
    for (size_t i = 0; i < p.isps.size(); ++i) {
        if (i) os << ",";
        const auto& v = p.isps[i];
        os << "{\"id\":" << jsonStr(v.id) << ",\"label\":" << jsonStr(v.label)
           << ",\"default\":" << (v.isDefault ? "true" : "false") << "}";
    }
    os << "]";
    if (p.check.present) {
        os << ",\"check\":{\"method\":" << jsonStr(p.check.method) << ",\"url\":" << jsonStr(p.check.url)
           << ",\"online\":" << jsonOpt(p.check.onlineContains)
           << ",\"offline\":" << jsonOpt(p.check.offlineContains)
           << ",\"headers\":" << jsonHeaders(p.check.headers)
           << ",\"body\":" << jsonOpt(p.check.body) << "}";
    } else os << ",\"check\":null";
    os << ",\"steps\":[";
    for (size_t i = 0; i < p.steps.size(); ++i) {
        if (i) os << ",";
        const auto& s = p.steps[i];
        os << "{\"name\":" << jsonStr(s.name) << ",\"method\":" << jsonStr(s.method)
           << ",\"url\":" << jsonStr(s.url) << ",\"expectStatus\":";
        if (s.expectStatus) os << *s.expectStatus; else os << "null";
        os << ",\"success\":" << matchJson(s.success) << ",\"alreadyOnline\":" << matchJson(s.alreadyOnline)
           << ",\"captures\":[";
        for (size_t j = 0; j < s.captures.size(); ++j) {
            if (j) os << ",";
            const auto& c = s.captures[j];
            os << "{\"name\":" << jsonStr(c.name) << ",\"jsonPath\":" << jsonOpt(c.jsonPath)
               << ",\"regex\":" << jsonOpt(c.regex) << ",\"group\":" << c.group << "}";
        }
        os << "],\"failJsonPath\":" << jsonOpt(s.failJsonPath)
           << ",\"headers\":" << jsonHeaders(s.headers)
           << ",\"body\":" << jsonOpt(s.body) << "}";
    }
    os << "]";
    os << ",\"headers\":" << jsonHeaders(p.headers);
    os << ",\"timing\":{\"ipPollMs\":" << p.timing.ipPollMs
       << ",\"maxIpWaitMs\":" << p.timing.maxIpWaitMs
       << ",\"skipNetworkReadyOnNotify\":" << (p.timing.skipNetworkReadyOnNotify ? "true" : "false")
       << ",\"connectTimeoutMs\":" << p.timing.connectTimeoutMs
       << ",\"readTimeoutMs\":" << p.timing.readTimeoutMs << "}";
    os << ",\"policy\":{\"bindProcess\":" << (p.policy.bindProcess ? "true" : "false")
       << ",\"promoteNetwork\":" << (p.policy.promoteNetwork ? "true" : "false")
       << ",\"rootSetDefault\":" << (p.policy.rootSetDefault ? "true" : "false")
       << ",\"authOverWifi\":" << (p.policy.authOverWifi ? "true" : "false")
       << ",\"preferredIfaces\":[";
    for (size_t i = 0; i < p.policy.preferredIfaces.size(); ++i) {
        if (i) os << ","; os << jsonStr(p.policy.preferredIfaces[i]);
    }
    os << "]}";
    os << ",\"detect\":{\"retryCount\":" << p.detect.retryCount
       << ",\"retryDelayMs\":" << p.detect.retryDelayMs << "}";
    os << "}";
    return os.str();
}

// ---------------------------------------------------------------------------
// 结构校验与映射
// ---------------------------------------------------------------------------

static const std::set<std::string> ROOT_KEYS = {
    "id","name","version","description","network","sandbox","sandbox_hosts",
    "fields","isps","check","login","timing","detect","headers"
};
static const std::set<std::string> NETWORK_KEYS = {
    "ssid","bind_process","promote_network","root_set_default","preferred_ifaces","auth_over_wifi"
};
static const std::set<std::string> TIMING_KEYS = {
    "ip_poll_ms","max_ip_wait_ms","skip_network_ready_on_notify","connect_timeout_ms","read_timeout_ms"
};
static const std::set<std::string> DETECT_KEYS = {"retry_count","retry_delay_ms"};
static const std::set<std::string> SANDBOX_KEYS = {"hosts"};
static const std::set<std::string> FIELD_KEYS = {"id","name","type","required"};
static const std::set<std::string> ISP_KEYS = {"id","suffix","name","label","default"};
static const std::set<std::string> CHECK_KEYS = {"method","url","request","online_when","offline_when","headers"};
static const std::set<std::string> MATCH_KEYS = {"contains","regex","json","json_path","json_equals"};
static const std::set<std::string> JSON_KEYS = {"path","equals"};
static const std::set<std::string> LOGIN_KEYS = {"steps"};
static const std::set<std::string> STEP_KEYS = {
    "name","method","url","request","expect_status","headers","success_when","already_online_when","capture","fail"
};
static const std::set<std::string> CAPTURE_KEYS = {"name","json_path","regex","group"};
static const std::set<std::string> FAIL_KEYS = {"json_path"};

static void checkKeys(const YValue& map, const std::set<std::string>& allowed, const std::string& where) {
    if (map.type != YValue::Type::Map) throw PluginError(where + " 必须是键值映射");
    for (auto& kv : map.map) {
        if (kv.first.empty()) throw PluginError(where + " 出现空键");
        if (!allowed.count(kv.first)) {
            throw PluginError(where + " 出现未知键 \"" + kv.first + "\"。可用键：" +
                             [&] {
                                 std::string s;
                                 for (auto& k : allowed) { if (!s.empty()) s += ", "; s += k; }
                                 return s;
                             }());
        }
    }
}

static const YValue* section(const YValue& map, const std::string& key) {
    const YValue* v = map.get(key);
    if (!v || v->type == YValue::Type::Null) return nullptr;
    if (v->type != YValue::Type::Map) throw PluginError(key + " 必须是键值映射（检查缩进）");
    return v;
}

static std::optional<std::string> strOpt(const YValue& map, const std::string& key, const std::string& where) {
    const YValue* v = map.get(key);
    if (!v || v->type == YValue::Type::Null) return std::nullopt;
    if (v->type == YValue::Type::Map || v->type == YValue::Type::List) {
        throw PluginError(where + "." + key + " 应该是一段文本");
    }
    std::string s = trim(v->type == YValue::Type::Str ? v->str : (v->type == YValue::Type::Int ? std::to_string(v->integer) : v->boolean ? "true" : ""));
    if (s.empty()) return std::nullopt;
    return s;
}

static std::string strOr(const YValue& map, const std::string& key, const std::string& where, const std::string& def) {
    auto v = strOpt(map, key, where);
    return v ? *v : def;
}

static int intOr(const YValue& map, const std::string& key, const std::string& where, int def) {
    const YValue* v = map.get(key);
    if (!v || v->type == YValue::Type::Null) return def;
    if (v->type == YValue::Type::Int) return (int)v->integer;
    if (v->type == YValue::Type::Str) {
        char* end = nullptr;
        long n = std::strtol(v->str.c_str(), &end, 10);
        if (end && *end == '\0') return (int)n;
    }
    throw PluginError(where + "." + key + " 必须是整数");
}

static long long longOr(const YValue& map, const std::string& key, const std::string& where, long long def) {
    const YValue* v = map.get(key);
    if (!v || v->type == YValue::Type::Null) return def;
    if (v->type == YValue::Type::Int) return v->integer;
    if (v->type == YValue::Type::Str) {
        char* end = nullptr;
        long long n = std::strtoll(v->str.c_str(), &end, 10);
        if (end && *end == '\0') return n;
    }
    throw PluginError(where + "." + key + " 必须是整数");
}

static bool boolOr(const YValue& map, const std::string& key, const std::string& where, bool def) {
    const YValue* v = map.get(key);
    if (!v || v->type == YValue::Type::Null) return def;
    if (v->type == YValue::Type::Bool) return v->boolean;
    std::string s = lowerAscii(trim(v->type == YValue::Type::Str ? v->str : ""));
    if (s == "true" || s == "yes" || s == "1") return true;
    if (s == "false" || s == "no" || s == "0") return false;
    throw PluginError(where + "." + key + " 不是布尔值：只能是 true 或 false");
}

static Headers readHeaders(const YValue* raw, const std::string& where) {
    Headers out;
    if (!raw || raw->type == YValue::Type::Null) return out;
    if (raw->type != YValue::Type::Map) throw PluginError(where + " 必须是键值映射");
    for (auto& kv : raw->map) {
        if (kv.first.empty()) throw PluginError(where + " 出现空请求头名");
        if (kv.second.type == YValue::Type::Map || kv.second.type == YValue::Type::List) {
            throw PluginError(where + "." + kv.first + " 必须是文本");
        }
        std::string value = kv.second.type == YValue::Type::Str ? kv.second.str
                            : kv.second.type == YValue::Type::Int ? std::to_string(kv.second.integer)
                            : kv.second.type == YValue::Type::Bool ? (kv.second.boolean ? "true" : "false") : "";
        value = trim(value);
        if (value.empty()) throw PluginError(where + "." + kv.first + " 是空值");
        out.push_back({kv.first, value});
    }
    return out;
}

static Headers mergeHeaders(const Headers& base, const Headers& extra) {
    Headers out = base;
    for (auto& h : extra) {
        for (auto it = out.begin(); it != out.end(); ++it) {
            if (iequals(it->name, h.name)) { out.erase(it); break; }
        }
        out.push_back(h);
    }
    return out;
}

static std::string headerValue(const Headers& h, const std::string& name) {
    for (auto& x : h) if (iequals(x.name, name)) return x.value;
    return "";
}

static void validateUrl(const std::string& url, const std::vector<std::string>& hosts, const std::string& where) {
    std::smatch m;
    std::regex re("^(https?)://([^/?#]+)", std::regex::icase);
    if (!std::regex_search(url, m, re)) throw PluginError(where + " 必须是 http(s) 绝对地址");
    std::string scheme = lowerAscii(m[1].str());
    std::string authority = m[2].str();
    size_t at = authority.rfind('@');
    if (at != std::string::npos) authority = authority.substr(at + 1);
    std::string host;
    int port;
    size_t colon = authority.rfind(':');
    if (colon != std::string::npos && colon + 1 < authority.size()) {
        char* end = nullptr;
        long p = std::strtol(authority.c_str() + colon + 1, &end, 10);
        if (end && *end == '\0') { host = authority.substr(0, colon); port = (int)p; }
        else { host = authority; port = scheme == "https" ? 443 : 80; }
    } else { host = authority; port = scheme == "https" ? 443 : 80; }
    host = lowerAscii(host);
    if (host.empty()) throw PluginError(where + " 里没有主机名");
    bool allowed = false;
    for (auto& entry : hosts) {
        size_t ec = entry.find(':');
        if (ec == std::string::npos) {
            if (entry == host) { allowed = true; break; }
        } else {
            std::string eh = entry.substr(0, ec);
            int ep = std::atoi(entry.c_str() + ec + 1);
            if (eh == host && ep == port) { allowed = true; break; }
        }
    }
    if (!allowed) throw PluginError(where + " 的主机 " + host + ":" + std::to_string(port) + " 不在沙盒白名单内");
}


struct RawRequest {
    std::string method, url;
    Headers headers;
    std::optional<std::string> body;
};

static RawRequest parseRawRequest(const std::string& raw, const std::string& where,
                                  const std::vector<std::string>& hosts) {
    std::string normalized = raw;
    // normalize CRLF / CR
    std::string n2;
    for (size_t i = 0; i < normalized.size(); ++i) {
        if (normalized[i] == '\r') {
            if (i + 1 < normalized.size() && normalized[i + 1] == '\n') continue;
            n2.push_back('\n');
        } else n2.push_back(normalized[i]);
    }
    normalized.swap(n2);
    size_t blank = normalized.find("\n\n");
    std::string head = blank == std::string::npos ? normalized : normalized.substr(0, blank);
    std::optional<std::string> body;
    if (blank != std::string::npos) body = normalized.substr(blank + 2);
    std::vector<std::string> lines;
    std::istringstream ss(head);
    std::string line;
    while (std::getline(ss, line)) lines.push_back(line);
    if (lines.empty()) throw PluginError(where + " 为空");
    std::smatch m;
    std::regex req("^([A-Z]+)\\s+(\\S+)\\s+HTTP/1\\.[01]$");
    std::string requestLine = trim(lines[0]);
    if (!std::regex_match(requestLine, m, req)) throw PluginError(where + " 第一行请求行不合法");
    std::string method = m[1].str();
    if (method != "GET" && method != "POST") throw PluginError(where + " 只支持 GET/POST");
    std::string target = m[2].str();
    std::string hostHeader;
    Headers hs;
    for (size_t i = 1; i < lines.size(); ++i) {
        if (trim(lines[i]).empty()) continue;
        size_t colon = lines[i].find(':');
        if (colon == std::string::npos || colon == 0) throw PluginError(where + " 有非法请求头");
        std::string name = trim(lines[i].substr(0, colon));
        std::string value = trim(lines[i].substr(colon + 1));
        if (name.empty() || value.empty()) throw PluginError(where + " 请求头名或值为空");
        if (iequals(name, "Host")) hostHeader = value;
        else if (iequals(name, "Content-Length")) { /* 传输层重写 */ }
        else {
            for (auto it = hs.begin(); it != hs.end(); ++it) if (iequals(it->name, name)) { hs.erase(it); break; }
            hs.push_back({name, value});
        }
    }
    std::string url;
    if (startsWith(lowerAscii(target), "http://") || startsWith(lowerAscii(target), "https://")) url = target;
    else {
        if (hostHeader.empty()) throw PluginError(where + " 缺少 Host 请求头");
        url = "http://" + hostHeader + (startsWith(target, "/") ? target : "/" + target);
    }
    validateUrl(url, hosts, where + ".url");
    return RawRequest{method, url, hs, method == "POST" ? body.value_or("") : body};
}

static MatchDef readMatch(const YValue& m, const std::string& where) {
    checkKeys(m, MATCH_KEYS, where);
    MatchDef def;
    def.present = true;
    def.bodyContains = strOpt(m, "contains", where);
    def.bodyRegex = strOpt(m, "regex", where);
    const YValue* json = section(m, "json");
    if (json) {
        checkKeys(*json, JSON_KEYS, where + ".json");
        def.jsonPath = strOpt(*json, "path", where + ".json");
        def.jsonEquals = strOpt(*json, "equals", where + ".json");
    }
    if (!def.jsonPath) def.jsonPath = strOpt(m, "json_path", where);
    if (!def.jsonEquals) def.jsonEquals = strOpt(m, "json_equals", where);
    if (!def.bodyContains && !def.bodyRegex && !def.jsonPath && !def.jsonEquals) {
        throw PluginError(where + " 没有有效条件");
    }
    if (def.bodyRegex) {
        try { std::regex(*def.bodyRegex); }
        catch (...) { throw PluginError(where + ".regex 正则表达式非法"); }
    }
    return def;
}

static std::vector<CaptureDef> readCaptures(const YValue* raw, const std::string& where) {
    std::vector<CaptureDef> out;
    if (!raw || raw->type == YValue::Type::Null) return out;
    if (raw->type != YValue::Type::List) throw PluginError(where + ".capture 必须是数组");
    std::set<std::string> names;
    for (size_t i = 0; i < raw->list.size(); ++i) {
        const YValue& item = raw->list[i];
        if (item.type != YValue::Type::Map) throw PluginError(where + ".capture[" + std::to_string(i) + "] 必须是键值映射");
        checkKeys(item, CAPTURE_KEYS, where + ".capture[" + std::to_string(i) + "]");
        std::string name = strOr(item, "name", where + ".capture[" + std::to_string(i) + "]", "");
        if (name.empty()) throw PluginError(where + ".capture[" + std::to_string(i) + "] 缺少 name");
        if (!names.insert(name).second) throw PluginError(where + ".capture name 重复: " + name);
        CaptureDef c;
        c.name = name;
        c.jsonPath = strOpt(item, "json_path", where);
        c.regex = strOpt(item, "regex", where);
        c.group = intOr(item, "group", where, 1);
        if (!c.jsonPath && !c.regex) throw PluginError(where + ".capture 至少写 regex 或 json_path");
        if (c.regex) {
            try { std::regex(*c.regex); }
            catch (...) { throw PluginError(where + ".capture.regex 正则表达式非法"); }
        }
        out.push_back(std::move(c));
    }
    return out;
}

static std::vector<std::string> readStringList(const YValue* raw, const std::string& where, bool commaSplit = false) {
    std::vector<std::string> out;
    if (!raw || raw->type == YValue::Type::Null) return out;
    if (raw->type == YValue::Type::List) {
        for (auto& v : raw->list) {
            if (v.type == YValue::Type::Map || v.type == YValue::Type::List) throw PluginError(where + " 条目必须是文本");
            std::string s = trim(v.type == YValue::Type::Str ? v.str : v.type == YValue::Type::Int ? std::to_string(v.integer) : "");
            if (s.empty()) throw PluginError(where + " 有空条目");
            out.push_back(s);
        }
    } else if (raw->type == YValue::Type::Str && commaSplit) {
        std::string text = trim(raw->str);
        // 兼容老插件里的简单 flow 列表：[wlan0, rmnet, eth0]。只剥外层方括号，
        // 不实现通用 flow 集合；真正的块列表仍走上面的 List 分支。
        if (text.size() >= 2 && text.front() == '[' && text.back() == ']') {
            text = text.substr(1, text.size() - 2);
        }
        std::istringstream ss(text);
        std::string item;
        while (std::getline(ss, item, ',')) {
            item = trim(item);
            if (!item.empty()) out.push_back(item);
        }
    } else {
        throw PluginError(where + " 必须是数组");
    }
    return out;
}

static bool validHostName(const std::string& host) {
    if (host.empty()) return false;
    for (char c : host) {
        if (!(std::isalnum((unsigned char)c) || c == '.' || c == '_' || c == '-')) return false;
    }
    return true;
}

static std::string hostEntry(const YValue& item, const std::string& where) {
    if (item.type == YValue::Type::Map) {
        for (auto& kv : item.map) {
            if (kv.first != "host" && kv.first != "port") {
                throw PluginError(where + " 出现未知键 "" + kv.first + ""：映射写法只支持 host/port");
            }
        }
        const YValue* h = item.get("host");
        if (!h || h->type == YValue::Type::Null || h->type == YValue::Type::Map || h->type == YValue::Type::List) {
            throw PluginError(where + " 缺少 host 文本");
        }
        std::string host = trim(h->str);
        if (!validHostName(host)) throw PluginError(where + " 的 host 非法: " + host);
        int port = 443;
        if (const YValue* p = item.get("port")) {
            if (p->type == YValue::Type::Int) port = (int)p->integer;
            else if (p->type == YValue::Type::Str) {
                char* end = nullptr;
                long v = std::strtol(p->str.c_str(), &end, 10);
                if (!end || *end != '\0') throw PluginError(where + " 的 port 不是数字");
                port = (int)v;
            } else throw PluginError(where + " 的 port 不是数字");
            if (port < 1 || port > 65535) throw PluginError(where + " 的 port 超出 1~65535");
        }
        return lowerAscii(host) + ":" + std::to_string(port);
    }
    if (item.type != YValue::Type::Str) throw PluginError(where + " 条目必须是 host 或 { host, port }");
    std::string text = trim(item.str);
    if (text.empty()) throw PluginError(where + " 是空条目");
    if (text.find("://") != std::string::npos || text.find('/') != std::string::npos ||
        text.find('?') != std::string::npos || text.find('#') != std::string::npos ||
        text.find('@') != std::string::npos || text.find(' ') != std::string::npos) {
        throw PluginError(where + " 条目非法: " + text);
    }
    size_t colon = text.find(':');
    if (colon == std::string::npos) {
        if (!validHostName(text)) throw PluginError(where + " 的 host 非法: " + text);
        return lowerAscii(text); // 无端口：历史写法，允许任意端口
    }
    if (text.find(':', colon + 1) != std::string::npos) throw PluginError(where + " 冒号过多: " + text);
    std::string host = text.substr(0, colon);
    if (!validHostName(host)) throw PluginError(where + " 的 host 非法: " + host);
    std::string portText = text.substr(colon + 1);
    if (portText.empty()) throw PluginError(where + " 端口为空");
    char* end = nullptr;
    long port = std::strtol(portText.c_str(), &end, 10);
    if (!end || *end != '\0' || port < 1 || port > 65535) throw PluginError(where + " 端口非法: " + portText);
    return lowerAscii(host) + ":" + std::to_string(port);
}

static std::vector<std::string> readHosts(const YValue* raw, const std::string& where) {
    if (!raw || raw->type == YValue::Type::Null) throw PluginError("缺少沙盒主机白名单：请写 sandbox.hosts");
    std::vector<std::string> items;
    if (raw->type == YValue::Type::List) {
        if (raw->list.empty()) throw PluginError(where + " 不能为空数组");
        for (size_t i = 0; i < raw->list.size(); ++i) {
            items.push_back(hostEntry(raw->list[i], where + "[" + std::to_string(i) + "]"));
        }
    } else if (raw->type == YValue::Type::Str) {
        // 简单 flow 列表（老插件兼容）：[host:port, host:port]
        std::string text = trim(raw->str);
        if (text.size() >= 2 && text.front() == '[' && text.back() == ']') {
            text = text.substr(1, text.size() - 2);
        }
        std::istringstream ss(text);
        std::string item;
        while (std::getline(ss, item, ',')) {
            item = trim(item);
            if (!item.empty()) items.push_back(hostEntry(YValue::makeStr(item), where));
        }
        if (items.empty()) throw PluginError(where + " 不能为空数组");
    } else {
        throw PluginError(where + " 必须是数组");
    }
    std::vector<std::string> out;
    std::set<std::string> seen;
    for (size_t i = 0; i < items.size(); ++i) {
        if (!seen.insert(items[i]).second) throw PluginError(where + " 条目重复: " + items[i]);
        out.push_back(items[i]);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Plugin 对象构建
// ---------------------------------------------------------------------------

static std::vector<FieldDef> readFields(const YValue* raw) {
    std::vector<FieldDef> out;
    if (!raw || raw->type == YValue::Type::Null) return out;
    if (raw->type != YValue::Type::List) throw PluginError("fields 必须是数组");
    std::set<std::string> seen;
    for (size_t i = 0; i < raw->list.size(); ++i) {
        const YValue& m = raw->list[i];
        std::string where = "fields[" + std::to_string(i) + "]";
        if (m.type != YValue::Type::Map) throw PluginError(where + " 必须是键值映射");
        checkKeys(m, FIELD_KEYS, where);
        FieldDef f;
        f.id = strOr(m, "id", where, "");
        if (f.id.empty()) throw PluginError(where + " 缺少 id");
        if (!seen.insert(f.id).second) throw PluginError(where + " 的 id 重复");
        f.label = strOr(m, "name", where, f.id);
        std::string type = lowerAscii(strOr(m, "type", where, "text"));
        if (type != "text" && type != "password") throw PluginError(where + " 的 type 只支持 text/password");
        f.type = type;
        f.required = boolOr(m, "required", where, true);
        out.push_back(std::move(f));
    }
    return out;
}

static std::vector<IspDef> readIsps(const YValue* raw) {
    std::vector<IspDef> out;
    if (!raw || raw->type == YValue::Type::Null) return out;
    if (raw->type != YValue::Type::List) throw PluginError("isps 必须是数组");
    std::set<std::string> seen;
    for (size_t i = 0; i < raw->list.size(); ++i) {
        const YValue& m = raw->list[i];
        std::string where = "isps[" + std::to_string(i) + "]";
        if (m.type != YValue::Type::Map) throw PluginError(where + " 必须是键值映射");
        checkKeys(m, ISP_KEYS, where);
        IspDef d;
        d.id = strOr(m, "id", where, strOr(m, "suffix", where, ""));
        if (d.id.empty()) throw PluginError(where + " 缺少 id");
        if (!seen.insert(d.id).second) throw PluginError(where + " 的 id 重复");
        d.label = strOr(m, "name", where, strOr(m, "label", where, d.id));
        d.isDefault = boolOr(m, "default", where, false);
        out.push_back(std::move(d));
    }
    return out;
}

static std::optional<std::string> simpleContains(const YValue& m, const std::string& key) {
    const YValue* raw = m.get(key);
    if (!raw) return std::nullopt;
    if (raw->type != YValue::Type::Map) throw PluginError("check." + key + " 必须是映射");
    for (auto& kv : raw->map) {
        if (kv.first != "contains") throw PluginError("check." + key + " 只支持 contains");
    }
    auto v = strOpt(*raw, "contains", "check." + key);
    if (!v) throw PluginError("check." + key + ".contains 不能为空");
    return v;
}

static std::optional<std::string> failJsonPath(const YValue& m, const std::string& where) {
    const YValue* raw = m.get("fail");
    if (!raw) return std::nullopt;
    if (raw->type != YValue::Type::Map) throw PluginError(where + ".fail 必须是映射");
    checkKeys(*raw, FAIL_KEYS, where + ".fail");
    auto v = strOpt(*raw, "json_path", where + ".fail");
    if (!v) throw PluginError(where + ".fail.json_path 不能为空");
    return v;
}

static Plugin buildPlugin(const YValue& root) {
    if (root.type != YValue::Type::Map) throw PluginError("插件根节点必须是 key: value 映射");
    checkKeys(root, ROOT_KEYS, "顶层");
    Plugin p;
    p.id = strOr(root, "id", "顶层", "");
    if (p.id.empty()) throw PluginError("缺少插件标识：顶层必须写 id");
    p.name = strOr(root, "name", "顶层", p.id);
    p.version = intOr(root, "version", "顶层", 1);
    p.description = strOr(root, "description", "顶层", "");

    const YValue* network = section(root, "network");
    if (network) {
        checkKeys(*network, NETWORK_KEYS, "network");
        p.ssids = readStringList(network->get("ssid"), "network.ssid", true);
    }

    const YValue* sandbox = section(root, "sandbox");
    if (sandbox) checkKeys(*sandbox, SANDBOX_KEYS, "sandbox");
    const YValue* hostRaw = sandbox ? sandbox->get("hosts") : root.get("sandbox_hosts");
    p.hosts = readHosts(hostRaw, sandbox ? "sandbox.hosts" : "sandbox_hosts");

    p.fields = readFields(root.get("fields"));
    p.isps = readIsps(root.get("isps"));

    const YValue* check = section(root, "check");
    if (check) {
        checkKeys(*check, CHECK_KEYS, "check");
        Headers extra = readHeaders(check->get("headers"), "check.headers");
        auto rawRequest = strOpt(*check, "request", "check");
        if (rawRequest) {
            if (check->get("method") || check->get("url")) {
                throw PluginError("check 同时写了 request 和 url/method");
            }
            RawRequest r = parseRawRequest(*rawRequest, "check.request", p.hosts);
            p.check.present = true;
            p.check.method = r.method;
            p.check.url = r.url;
            p.check.headers = mergeHeaders(r.headers, extra);
            p.check.body = r.body;
        } else {
            p.check.present = true;
            p.check.method = strOr(*check, "method", "check", "GET");
            p.check.method = [&] { std::string m = p.check.method; for (auto& c : m) c = (char)std::toupper((unsigned char)c); return m; }();
            if (p.check.method != "GET" && p.check.method != "POST") throw PluginError("check.method 只支持 GET/POST");
            p.check.url = strOr(*check, "url", "check", "");
            if (p.check.url.empty()) throw PluginError("check 缺少 url");
            validateUrl(p.check.url, p.hosts, "check.url");
            p.check.headers = extra;
            p.check.body = std::nullopt;
        }
        p.check.onlineContains = simpleContains(*check, "online_when");
        p.check.offlineContains = simpleContains(*check, "offline_when");
    }

    const YValue* login = section(root, "login");
    if (!login) throw PluginError("缺少 login 段");
    checkKeys(*login, LOGIN_KEYS, "login");
    const YValue* steps = login->get("steps");
    if (!steps || steps->type != YValue::Type::List || steps->list.empty()) {
        throw PluginError("login.steps 必须是非空数组");
    }
    std::set<std::string> names;
    for (size_t i = 0; i < steps->list.size(); ++i) {
        const YValue& m = steps->list[i];
        std::string where = "login.steps[" + std::to_string(i) + "]";
        if (m.type != YValue::Type::Map) throw PluginError(where + " 必须是键值映射");
        checkKeys(m, STEP_KEYS, where);
        StepDef s;
        Headers extra = readHeaders(m.get("headers"), where + ".headers");
        auto rawRequest = strOpt(m, "request", where);
        if (rawRequest) {
            if (m.get("method") || m.get("url")) throw PluginError(where + " 同时写了 request 和 url/method");
            RawRequest r = parseRawRequest(*rawRequest, where + ".request", p.hosts);
            s.method = r.method;
            s.url = r.url;
            s.headers = mergeHeaders(r.headers, extra);
            s.body = r.body;
        } else {
            s.url = strOr(m, "url", where, "");
            if (s.url.empty()) throw PluginError(where + " 缺少 url");
            validateUrl(s.url, p.hosts, where + ".url");
            s.method = strOr(m, "method", where, "GET");
            for (auto& c : s.method) c = (char)std::toupper((unsigned char)c);
            if (s.method != "GET" && s.method != "POST") throw PluginError(where + ".method 只支持 GET/POST");
            s.headers = extra;
            s.body = std::nullopt;
        }
        s.name = strOr(m, "name", where, "step" + std::to_string(i));
        if (!names.insert(s.name).second) throw PluginError(where + " 的 name 重复");
        if (m.get("expect_status")) s.expectStatus = intOr(m, "expect_status", where, 0);
        const YValue* success = m.get("success_when");
        if (success && success->type != YValue::Type::Null) s.success = readMatch(*success, where + ".success_when");
        const YValue* already = m.get("already_online_when");
        if (already && already->type != YValue::Type::Null) s.alreadyOnline = readMatch(*already, where + ".already_online_when");
        s.captures = readCaptures(m.get("capture"), where);
        s.failJsonPath = failJsonPath(m, where);
        p.steps.push_back(std::move(s));
    }

    const YValue* timing = section(root, "timing");
    if (timing) {
        checkKeys(*timing, TIMING_KEYS, "timing");
        p.timing.ipPollMs = longOr(*timing, "ip_poll_ms", "timing", 250);
        p.timing.maxIpWaitMs = longOr(*timing, "max_ip_wait_ms", "timing", 3000);
        p.timing.skipNetworkReadyOnNotify = boolOr(*timing, "skip_network_ready_on_notify", "timing", true);
        p.timing.connectTimeoutMs = intOr(*timing, "connect_timeout_ms", "timing", 6000);
        p.timing.readTimeoutMs = intOr(*timing, "read_timeout_ms", "timing", 6000);
        p.timing.ipPollMs = std::max(50LL, std::min(3000LL, p.timing.ipPollMs));
        p.timing.maxIpWaitMs = std::max(0LL, std::min(30000LL, p.timing.maxIpWaitMs));
        p.timing.connectTimeoutMs = std::max(500, std::min(30000, p.timing.connectTimeoutMs));
        p.timing.readTimeoutMs = std::max(500, std::min(30000, p.timing.readTimeoutMs));
    }
    if (network) {
        checkKeys(*network, NETWORK_KEYS, "network");
        p.policy.bindProcess = boolOr(*network, "bind_process", "network", true);
        p.policy.promoteNetwork = boolOr(*network, "promote_network", "network", true);
        p.policy.rootSetDefault = boolOr(*network, "root_set_default", "network", true);
        p.policy.authOverWifi = boolOr(*network, "auth_over_wifi", "network", true);
        p.policy.preferredIfaces = readStringList(network->get("preferred_ifaces"), "network.preferred_ifaces", true);
    }
    const YValue* detect = section(root, "detect");
    if (detect) {
        checkKeys(*detect, DETECT_KEYS, "detect");
        p.detect.retryCount = intOr(*detect, "retry_count", "detect", 3);
        p.detect.retryDelayMs = longOr(*detect, "retry_delay_ms", "detect", 5000);
        p.detect.retryCount = std::max(1, std::min(10, p.detect.retryCount));
        p.detect.retryDelayMs = std::max(200LL, std::min(60000LL, p.detect.retryDelayMs));
    }
    p.headers = readHeaders(root.get("headers"), "顶层.headers");
    return p;
}

static std::mutex g_registryMutex;
static std::map<std::string, Plugin> g_registry;

} // namespace

std::string pluginLoadJson(const std::string& yaml, const std::string& fileName, const std::string& source) {
    (void)fileName;
    (void)source;
    try {
        if (trim(yaml).empty()) throw PluginError("插件内容为空");
        YValue root = parseYaml(yaml);
        Plugin p = buildPlugin(root);
        {
            std::lock_guard<std::mutex> lock(g_registryMutex);
            g_registry[p.id] = p;
        }
        return std::string("{\"ok\":true,\"plugin\":") + pluginJson(p) + "}";
    } catch (const PluginError& e) {
        return std::string("{\"ok\":false,\"error\":") + jsonStr(e.what()) + "}";
    } catch (const std::exception& e) {
        return std::string("{\"ok\":false,\"error\":") + jsonStr(e.what()) + "}";
    }
}

// ---------------------------------------------------------------------------
// 轻量 JSON：解析回调/取值，足以处理门户的 JSONP 和内层 JSON。
// ---------------------------------------------------------------------------

struct JsonValue {
    enum class Type { Null, Str, Num, Bool, Obj, Arr };
    Type type = Type::Null;
    std::string str;
    double num = 0;
    bool boolean = false;
    std::map<std::string, JsonValue> obj;
    std::vector<JsonValue> arr;

    const JsonValue* get(const std::string& key) const {
        if (type != Type::Obj) return nullptr;
        auto it = obj.find(key);
        return it == obj.end() ? nullptr : &it->second;
    }
};

struct JsonParser {
    const std::string& s;
    size_t i = 0;
    explicit JsonParser(const std::string& text) : s(text) {}

    void ws() { while (i < s.size() && std::isspace((unsigned char)s[i])) ++i; }
    bool fail() const { return i >= s.size(); }

    JsonValue parse() {
        ws();
        JsonValue v = value();
        return v;
    }

    JsonValue value() {
        ws();
        if (i >= s.size()) return JsonValue{};
        char c = s[i];
        if (c == '"') return stringValue();
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == 't' || c == 'f') return boolValue();
        if (c == 'n') { i += 4; return JsonValue{}; }
        return numberValue();
    }

    JsonValue stringValue() {
        JsonValue v; v.type = JsonValue::Type::Str;
        if (s[i] != '"') return v;
        ++i;
        while (i < s.size()) {
            char c = s[i++];
            if (c == '"') break;
            if (c == '\\' && i < s.size()) {
                char n = s[i++];
                switch (n) {
                    case '"': v.str.push_back('"'); break;
                    case '\\': v.str.push_back('\\'); break;
                    case '/': v.str.push_back('/'); break;
                    case 'b': v.str.push_back('\b'); break;
                    case 'f': v.str.push_back('\f'); break;
                    case 'n': v.str.push_back('\n'); break;
                    case 'r': v.str.push_back('\r'); break;
                    case 't': v.str.push_back('\t'); break;
                    case 'u': {
                        unsigned code = 0;
                        for (int k = 0; k < 4 && i < s.size(); ++k) {
                            char h = s[i++];
                            code <<= 4;
                            if (h >= '0' && h <= '9') code |= h - '0';
                            else if (h >= 'a' && h <= 'f') code |= h - 'a' + 10;
                            else if (h >= 'A' && h <= 'F') code |= h - 'A' + 10;
                        }
                        // 仅处理 BMP；代理对不展开，保留原码点近似。
                        if (code < 0x80) v.str.push_back((char)code);
                        else if (code < 0x800) {
                            v.str.push_back((char)(0xC0 | (code >> 6)));
                            v.str.push_back((char)(0x80 | (code & 0x3F)));
                        } else {
                            v.str.push_back((char)(0xE0 | (code >> 12)));
                            v.str.push_back((char)(0x80 | ((code >> 6) & 0x3F)));
                            v.str.push_back((char)(0x80 | (code & 0x3F)));
                        }
                        break;
                    }
                    default: v.str.push_back(n);
                }
            } else v.str.push_back(c);
        }
        return v;
    }

    JsonValue numberValue() {
        JsonValue v; v.type = JsonValue::Type::Num;
        std::string n;
        while (i < s.size() && (std::isdigit((unsigned char)s[i]) || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) {
            n.push_back(s[i++]);
        }
        v.num = std::atof(n.c_str());
        return v;
    }

    JsonValue boolValue() {
        JsonValue v; v.type = JsonValue::Type::Bool;
        if (s[i] == 't') { v.boolean = true; i += 4; }
        else { v.boolean = false; i += 5; }
        return v;
    }

    JsonValue object() {
        JsonValue v; v.type = JsonValue::Type::Obj;
        ++i; // {
        while (i < s.size()) {
            ws();
            if (s[i] == '}') { ++i; break; }
            JsonValue key = stringValue();
            ws();
            if (i < s.size() && s[i] == ':') ++i;
            v.obj[key.str] = value();
            ws();
            if (i < s.size() && s[i] == ',') { ++i; continue; }
            if (i < s.size() && s[i] == '}') { ++i; break; }
        }
        return v;
    }

    JsonValue array() {
        JsonValue v; v.type = JsonValue::Type::Arr;
        ++i;
        while (i < s.size()) {
            ws();
            if (s[i] == ']') { ++i; break; }
            v.arr.push_back(value());
            ws();
            if (i < s.size() && s[i] == ',') { ++i; continue; }
            if (i < s.size() && s[i] == ']') { ++i; break; }
        }
        return v;
    }
};

static std::optional<JsonValue> parseJson(const std::string& body) {
    size_t start = body.find('{');
    size_t end = body.rfind('}');
    if (start == std::string::npos || end <= start) return std::nullopt;
    try {
        return JsonParser(body.substr(start, end - start + 1)).parse();
    } catch (...) {
        return std::nullopt;
    }
}

static std::string jsonScalarToString(const JsonValue* v) {
    if (!v) return "";
    switch (v->type) {
        case JsonValue::Type::Str: return v->str;
        case JsonValue::Type::Num: {
            char buf[64];
            if (v->num == (long long)v->num) snprintf(buf, sizeof(buf), "%lld", (long long)v->num);
            else snprintf(buf, sizeof(buf), "%.15g", v->num);
            return buf;
        }
        case JsonValue::Type::Bool: return v->boolean ? "true" : "false";
        case JsonValue::Type::Null: return "null";
        default: return "";
    }
}

static std::string jsonPathValue(const std::string& body, const std::string& path) {
    auto root = parseJson(body);
    if (!root) return "";
    const JsonValue* cur = &*root;
    std::istringstream ss(path);
    std::string seg;
    while (std::getline(ss, seg, '.')) {
        if (!cur || cur->type != JsonValue::Type::Obj) return "";
        cur = cur->get(seg);
    }
    return jsonScalarToString(cur);
}

// ---------------------------------------------------------------------------
// 运行期状态 / 模板展开
// ---------------------------------------------------------------------------

struct State {
    std::map<std::string, std::string> inputs;
    std::map<std::string, std::string> vars;
    std::string isp, ip, mac = "000000000000";
    int rand = 1000;
    std::optional<std::string> loginUrlOverride;
};

static State parseState(const std::string& json) {
    State st;
    auto root = parseJson(json);
    if (!root || root->type != JsonValue::Type::Obj) return st;
    if (const JsonValue* inputs = root->get("inputs"); inputs && inputs->type == JsonValue::Type::Obj) {
        for (auto& kv : inputs->obj) st.inputs[kv.first] = jsonScalarToString(&kv.second);
    }
    if (const JsonValue* v = root->get("isp")) st.isp = jsonScalarToString(v);
    if (const JsonValue* v = root->get("ip")) st.ip = jsonScalarToString(v);
    if (const JsonValue* v = root->get("mac")) st.mac = jsonScalarToString(v);
    if (const JsonValue* v = root->get("rand"); v && v->type == JsonValue::Type::Num) st.rand = (int)v->num;
    if (const JsonValue* v = root->get("loginUrlOverride"); v && v->type == JsonValue::Type::Str) st.loginUrlOverride = v->str;
    if (st.mac.empty()) st.mac = "000000000000";
    return st;
}

static std::optional<std::string> resolveKey(const State& st, const std::string& key) {
    if (key == "isp") return st.isp;
    if (key == "ip") return st.ip;
    if (key == "rand") return std::to_string(st.rand);
    if (key == "mac") return st.mac;
    auto it = st.inputs.find(key);
    if (it != st.inputs.end()) return it->second;
    auto vi = st.vars.find(key);
    if (vi != st.vars.end()) return vi->second;
    return std::nullopt;
}

static std::string expandTemplate(const std::string& tmpl, const State& st) {
    std::string out;
    out.reserve(tmpl.size());
    for (size_t i = 0; i < tmpl.size();) {
        if (tmpl[i] == '$' && i + 1 < tmpl.size() && tmpl[i + 1] == '{') {
            size_t end = tmpl.find('}', i + 2);
            if (end != std::string::npos && end > i + 2) {
                std::string key = trim(tmpl.substr(i + 2, end - i - 2));
                auto v = resolveKey(st, key);
                if (v) out += *v;
                i = end + 1;
                continue;
            }
        }
        out.push_back(tmpl[i++]);
    }
    return out;
}

static Headers expandHeaders(const Headers& in, const State& st) {
    Headers out;
    out.reserve(in.size());
    for (auto& h : in) out.push_back({h.name, expandTemplate(h.value, st)});
    return out;
}

static std::string headerBlock(const Headers& h) {
    std::string out;
    for (auto& x : h) {
        if (iequals(x.name, "Host") || iequals(x.name, "Content-Length") || iequals(x.name, "Connection") ||
            iequals(x.name, "Transfer-Encoding") || iequals(x.name, "Content-Encoding")) continue;
        if (x.name.find('\r') != std::string::npos || x.name.find('\n') != std::string::npos ||
            x.value.find('\r') != std::string::npos || x.value.find('\n') != std::string::npos) continue;
        out += x.name + ": " + x.value + "\r\n";
    }
    return out;
}

static bool contains(const std::string& body, const std::optional<std::string>& needle) {
    return needle && body.find(*needle) != std::string::npos;
}

static bool regexFind(const std::string& body, const std::optional<std::string>& pattern) {
    if (!pattern) return false;
    try { return std::regex_search(body, std::regex(*pattern)); } catch (...) { return false; }
}

static bool matchDef(const MatchDef& m, const std::string& body) {
    if (!m.present) return false;
    if (m.bodyContains && body.find(*m.bodyContains) != std::string::npos) return true;
    if (m.bodyRegex && regexFind(body, m.bodyRegex)) return true;
    if (m.jsonPath && m.jsonEquals) {
        std::string actual = jsonPathValue(body, *m.jsonPath);
        if (!actual.empty() && actual == *m.jsonEquals) return true;
    }
    return false;
}

static std::optional<std::string> captureValue(const CaptureDef& c, const std::string& body) {
    if (c.jsonPath) {
        std::string v = jsonPathValue(body, *c.jsonPath);
        if (!v.empty()) return v;
        return std::nullopt;
    }
    if (c.regex) {
        try {
            std::smatch m;
            if (std::regex_search(body, m, std::regex(*c.regex))) {
                if (c.group >= 0 && c.group < (int)m.size()) return m[c.group].str();
            }
        } catch (...) {}
    }
    return std::nullopt;
}

static std::string snippet(const std::string& body) {
    std::string b = trim(body);
    return b.size() <= 80 ? b : b.substr(0, 80);
}

struct RunResult {
    std::string outcome = "ERROR";
    std::string reason = "plugin_error";
    std::string detail;
    std::map<std::string, std::string> vars;
};

static std::string resultJson(const RunResult& r) {
    std::string out = "{\"outcome\":" + jsonStr(r.outcome) + ",\"reason\":" + jsonStr(r.reason) +
                      ",\"detail\":" + jsonStr(r.detail) + ",\"vars\":{";
    bool first = true;
    for (auto& kv : r.vars) {
        if (!first) out += ",";
        first = false;
        out += jsonStr(kv.first) + ":" + jsonStr(kv.second);
    }
    out += "}}";
    return out;
}

static std::string applyBaseOverride(const std::string& url, const std::optional<std::string>& base) {
    if (!base || trim(*base).empty() || base->find("${") != std::string::npos) return url;
    std::string clean = *base;
    size_t q = clean.find('?');
    if (q != std::string::npos) clean = clean.substr(0, q);
    size_t h = clean.find('#');
    if (h != std::string::npos) clean = clean.substr(0, h);
    clean = trim(clean);
    if (clean.empty()) return url;
    size_t q2 = url.find('?');
    std::string query = q2 == std::string::npos ? "" : url.substr(q2 + 1);
    return query.empty() ? clean : clean + "?" + query;
}

// ---------------------------------------------------------------------------
// 请求与 check/login 状态机
// ---------------------------------------------------------------------------

struct HttpOutcome {
    bool ok = false;
    int code = -1;
    std::string body;
    std::string error;
};

static HttpOutcome performRequest(const Plugin& p, const std::string& method,
                                  const std::string& url, const std::optional<std::string>& body,
                                  const Headers& headers) {
    HttpOutcome out;
    try {
        validateUrl(url, p.hosts, "runtime.url");
    } catch (const PluginError& e) {
        out.error = e.what();
        return out;
    }
    if (startsWith(lowerAscii(url), "https://")) {
        // 原生 socket 只实现 HTTP；HTTPS 继续由 Kotlin 的 Java 回退路径处理。
        out.error = "native_https_unsupported";
        return out;
    }
    std::string hb = headerBlock(headers);
    std::string referer = headerValue(headers, "Referer");
    HttpResult r = httpRequest(method, url, body.value_or(""), referer, hb,
                               std::max(p.timing.connectTimeoutMs, p.timing.readTimeoutMs));
    if (!r.error.empty()) {
        out.error = r.error;
        return out;
    }
    out.ok = true;
    out.code = r.code;
    out.body = std::move(r.body);
    return out;
}

static RunResult runCheck(const Plugin& p, State& st) {
    RunResult rr;
    if (!p.check.present) {
        rr.outcome = "ERROR";
        rr.reason = "no_check";
        return rr;
    }
    std::string url = expandTemplate(p.check.url, st);
    std::optional<std::string> body;
    if (p.check.body) body = expandTemplate(*p.check.body, st);
    Headers headers = expandHeaders(mergeHeaders(p.headers, p.check.headers), st);
    HttpOutcome resp = performRequest(p, p.check.method, url, body, headers);
    if (!resp.ok) {
        rr.outcome = "UNREACHABLE";
        rr.reason = resp.error.empty() ? "transport" : resp.error;
        rr.detail = rr.reason;
        return rr;
    }
    if (contains(resp.body, p.check.onlineContains)) {
        rr.outcome = "ONLINE";
        rr.reason = "online";
    } else if (contains(resp.body, p.check.offlineContains)) {
        rr.outcome = "OFFLINE";
        rr.reason = "offline";
    } else {
        rr.outcome = "ERROR";
        rr.reason = "check_unmatched";
        rr.detail = snippet(resp.body);
    }
    for (auto& kv : st.vars) rr.vars[kv.first] = kv.second;
    return rr;
}

static RunResult runLogin(const Plugin& p, State& st) {
    RunResult rr;
    int stepIndex = 0;
    for (const StepDef& step : p.steps) {
        std::string expanded = expandTemplate(step.url, st);
        if (stepIndex == 0) expanded = applyBaseOverride(expanded, st.loginUrlOverride);
        ++stepIndex;
        std::optional<std::string> body;
        if (step.body) body = expandTemplate(*step.body, st);
        Headers headers = expandHeaders(mergeHeaders(p.headers, step.headers), st);
        HttpOutcome resp = performRequest(p, step.method, expanded, body, headers);
        if (!resp.ok) {
            rr.outcome = "ERROR";
            rr.reason = resp.error.empty() ? "transport" : resp.error;
            rr.detail = rr.reason;
            for (auto& kv : st.vars) rr.vars[kv.first] = kv.second;
            return rr;
        }
        for (const CaptureDef& c : step.captures) {
            auto value = captureValue(c, resp.body);
            if (value) {
                st.vars[c.name] = *value;
                rr.vars[c.name] = *value;
            }
        }
        if (step.expectStatus && resp.code != *step.expectStatus) {
            rr.outcome = "FAIL";
            rr.reason = "http_status_" + std::to_string(resp.code);
            rr.detail = step.failJsonPath ? jsonPathValue(resp.body, *step.failJsonPath) : snippet(resp.body);
            return rr;
        }
        if (matchDef(step.success, resp.body)) {
            rr.outcome = "SUCCESS";
            rr.reason = "success";
            for (auto& kv : st.vars) rr.vars[kv.first] = kv.second;
            return rr;
        }
        if (matchDef(step.alreadyOnline, resp.body)) {
            rr.outcome = "ALREADY_ONLINE";
            rr.reason = "already_online";
            for (auto& kv : st.vars) rr.vars[kv.first] = kv.second;
            return rr;
        }
        if (step.success.present) {
            rr.outcome = "FAIL";
            rr.reason = "match_failed";
            rr.detail = step.failJsonPath ? jsonPathValue(resp.body, *step.failJsonPath) : snippet(resp.body);
            return rr;
        }
        if (resp.code < 200 || resp.code > 399) {
            rr.outcome = "FAIL";
            rr.reason = "http_status_" + std::to_string(resp.code);
            rr.detail = step.failJsonPath ? jsonPathValue(resp.body, *step.failJsonPath) : snippet(resp.body);
            return rr;
        }
    }
    rr.outcome = "SUCCESS";
    rr.reason = "success";
    for (auto& kv : st.vars) rr.vars[kv.first] = kv.second;
    return rr;
}

static std::string pluginRun(const std::string& pluginId, const std::string& stateJson, bool login) {
    Plugin p;
    {
        std::lock_guard<std::mutex> lock(g_registryMutex);
        auto it = g_registry.find(pluginId);
        if (it == g_registry.end()) {
            RunResult rr;
            rr.outcome = "ERROR";
            rr.reason = "plugin_not_loaded";
            rr.detail = pluginId;
            return resultJson(rr);
        }
        p = it->second;
    }
    State st = parseState(stateJson);
    RunResult rr = login ? runLogin(p, st) : runCheck(p, st);
    return resultJson(rr);
}

std::string pluginCheckJson(const std::string& pluginId, const std::string& stateJson) {
    return pluginRun(pluginId, stateJson, false);
}

std::string pluginLoginJson(const std::string& pluginId, const std::string& stateJson) {
    return pluginRun(pluginId, stateJson, true);
}

} // namespace lumina
