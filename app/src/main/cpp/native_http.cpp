#include "native_http.h"

#include <sys/socket.h>
#include <sys/select.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <sstream>

namespace lumina {

// 单次响应体上限，防止异常巨大的门户页面把内存打满（与 Java 侧 MAX_BODY_CHARS 对齐）
static const int kMaxBodyBytes = 512 * 1024;
static const char* kUserAgent =
    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/153.0.0.0 Mobile Safari/537.36";

static std::string g_bindIface;

void setBindInterface(const std::string& iface) {
    g_bindIface = iface;
}

static int bindSocketToIface(int sock) {
    if (g_bindIface.empty()) return 0;
    return setsockopt(sock, SOL_SOCKET, SO_BINDTODEVICE,
                      g_bindIface.c_str(), g_bindIface.size());
}

struct UrlParts {
    std::string host;
    int port = 80;
    std::string path = "/";
    bool ok = false;
};

static UrlParts parseUrl(const std::string& url) {
    UrlParts p;
    if (url.compare(0, 7, "http://") != 0) return p;
    std::string rest = url.substr(7);
    size_t slash = rest.find('/');
    std::string hostPort = (slash == std::string::npos) ? rest : rest.substr(0, slash);
    p.path = (slash == std::string::npos) ? "/" : rest.substr(slash);
    if (p.path.empty()) p.path = "/";
    size_t colon = hostPort.find(':');
    if (colon != std::string::npos) {
        p.host = hostPort.substr(0, colon);
        p.port = atoi(hostPort.substr(colon + 1).c_str());
    } else {
        p.host = hostPort;
        p.port = 80;
    }
    p.ok = !p.host.empty() && p.port > 0;
    return p;
}

// 非阻塞 connect + select 超时；成功返回 socket，失败返回 -1 并写入 err
static int connectWithTimeout(const std::string& host, int port, int timeoutMs, std::string& err) {
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    char portStr[16];
    snprintf(portStr, sizeof(portStr), "%d", port);
    if (getaddrinfo(host.c_str(), portStr, &hints, &res) != 0 || res == nullptr) {
        err = "dns_failed";
        return -1;
    }
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) {
        freeaddrinfo(res);
        err = "socket_failed";
        return -1;
    }
    bindSocketToIface(sock);

    fcntl(sock, F_SETFL, O_NONBLOCK);
    int rc = connect(sock, res->ai_addr, res->ai_addrlen);
    if (rc < 0 && errno != EINPROGRESS) {
        close(sock);
        freeaddrinfo(res);
        err = "connect_failed";
        return -1;
    }
    if (rc < 0) {
        fd_set wfds;
        FD_ZERO(&wfds);
        FD_SET(sock, &wfds);
        struct timeval tv;
        tv.tv_sec = timeoutMs / 1000;
        tv.tv_usec = (timeoutMs % 1000) * 1000;
        rc = select(sock + 1, nullptr, &wfds, nullptr, &tv);
        if (rc <= 0) {
            close(sock);
            freeaddrinfo(res);
            err = (rc == 0) ? "connect_timeout" : "select_error";
            return -1;
        }
        int soErr = 0;
        socklen_t len = sizeof(soErr);
        if (getsockopt(sock, SOL_SOCKET, SO_ERROR, &soErr, &len) < 0 || soErr != 0) {
            close(sock);
            freeaddrinfo(res);
            err = "connect_refused";
            return -1;
        }
    }
    freeaddrinfo(res);
    fcntl(sock, F_SETFL, 0);
    int one = 1;
    setsockopt(sock, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    return sock;
}

static bool sendAll(int sock, const std::string& data) {
    size_t sent = 0;
    while (sent < data.size()) {
        ssize_t n = send(sock, data.c_str() + sent, data.size() - sent, 0);
        if (n <= 0) return false;
        sent += (size_t)n;
    }
    return true;
}

// 读满整个响应（Connection: close）；带总超时保护
static std::string readAll(int sock, int timeoutMs) {
    std::string out;
    out.reserve(8192);
    fd_set rfds;
    struct timeval tv;
    int remaining = timeoutMs;
    while (remaining > 0) {
        FD_ZERO(&rfds);
        FD_SET(sock, &rfds);
        tv.tv_sec = remaining / 1000;
        tv.tv_usec = (remaining % 1000) * 1000;
        int rc = select(sock + 1, &rfds, nullptr, nullptr, &tv);
        if (rc <= 0) break;
        char buf[4096];
        ssize_t n = recv(sock, buf, sizeof(buf), 0);
        if (n <= 0) break;
        out.append(buf, (size_t)n);
        if ((int)out.size() >= kMaxBodyBytes) break;
    }
    return out;
}

// 解析 Transfer-Encoding: chunked 的响应体
static std::string decodeChunked(const std::string& raw) {
    std::string out;
    size_t pos = 0;
    while (pos < raw.size()) {
        size_t lineEnd = raw.find("\r\n", pos);
        if (lineEnd == std::string::npos) break;
        std::string sizeLine = raw.substr(pos, lineEnd - pos);
        size_t semi = sizeLine.find(';');
        if (semi != std::string::npos) sizeLine = sizeLine.substr(0, semi);
        long chunk = strtol(sizeLine.c_str(), nullptr, 16);
        pos = lineEnd + 2;
        if (chunk <= 0) break;
        if (pos + (size_t)chunk > raw.size()) {
            out.append(raw, pos, raw.size() - pos);
            break;
        }
        out.append(raw, pos, (size_t)chunk);
        pos += (size_t)chunk;
        if (raw.compare(pos, 2, "\r\n") == 0) pos += 2;
        if ((int)out.size() >= kMaxBodyBytes) break;
    }
    return out;
}

static std::string toLower(std::string s) {
    for (size_t i = 0; i < s.size(); ++i) {
        if (s[i] >= 'A' && s[i] <= 'Z') s[i] = (char)(s[i] - 'A' + 'a');
    }
    return s;
}

static void parseResponse(const std::string& raw, HttpResult& r) {
    size_t headEnd = raw.find("\r\n\r\n");
    std::string head = (headEnd == std::string::npos) ? raw : raw.substr(0, headEnd);
    std::string bodyRaw = (headEnd == std::string::npos) ? std::string() : raw.substr(headEnd + 4);

    // 状态码："HTTP/1.1 200 OK"
    size_t sp = head.find(' ');
    if (sp != std::string::npos) {
        r.code = atoi(head.c_str() + sp + 1);
    }
    if (r.code == 0) {
        r.error = "bad_response";
        return;
    }

    bool chunked = toLower(head).find("transfer-encoding: chunked") != std::string::npos;
    r.body = chunked ? decodeChunked(bodyRaw) : bodyRaw;
    if ((int)r.body.size() > kMaxBodyBytes) r.body.resize(kMaxBodyBytes);
}

HttpResult httpRequest(const std::string& method,
                       const std::string& url,
                       const std::string& body,
                       const std::string& referer,
                       int timeoutMs) {
    HttpResult r;
    UrlParts p = parseUrl(url);
    if (!p.ok) {
        r.error = "only_http_url";
        return r;
    }
    if (timeoutMs <= 0) timeoutMs = 8000;

    std::string err;
    int sock = connectWithTimeout(p.host, p.port, timeoutMs, err);
    if (sock < 0) {
        r.error = err;
        return r;
    }

    std::ostringstream req;
    req << method << " " << p.path << " HTTP/1.1\r\n";
    req << "Host: " << p.host << "\r\n";
    req << "User-Agent: " << kUserAgent << "\r\n";
    req << "Accept: */*\r\n";
    if (!referer.empty()) req << "Referer: " << referer << "\r\n";
    if (method == "POST") {
        req << "Content-Type: application/x-www-form-urlencoded\r\n";
        req << "Content-Length: " << body.size() << "\r\n";
    }
    req << "Connection: close\r\n\r\n";
    if (method == "POST") req << body;

    if (!sendAll(sock, req.str())) {
        close(sock);
        r.error = "send_failed";
        return r;
    }
    std::string raw = readAll(sock, timeoutMs);
    close(sock);
    if (raw.empty()) {
        r.error = "read_timeout";
        return r;
    }
    parseResponse(raw, r);
    return r;
}

bool tcpPing(const std::string& host, int port, int timeoutMs) {
    std::string err;
    int sock = connectWithTimeout(host, port, timeoutMs, err);
    if (sock < 0) return false;
    close(sock);
    return true;
}

std::string resolveHost(const std::string& host) {
    struct addrinfo hints;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    struct addrinfo* res = nullptr;
    if (getaddrinfo(host.c_str(), nullptr, &hints, &res) != 0 || res == nullptr) return "";
    char buf[64];
    struct sockaddr_in* sa = (struct sockaddr_in*)res->ai_addr;
    inet_ntop(AF_INET, &sa->sin_addr, buf, sizeof(buf));
    freeaddrinfo(res);
    return std::string(buf);
}

} // namespace lumina
