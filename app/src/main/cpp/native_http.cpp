#include "native_http.h"

#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/select.h>
#include <cstring>
#include <cstdlib>
#include <sstream>

namespace lumina {

static std::string urlEncode(const std::string& s) { return s; }

HttpResult httpGet(const std::string& url, const std::string& referer, int timeoutMs) {
    HttpResult r;
    // Parse URL: http://host:port/path
    if (url.substr(0, 7) != "http://") {
        r.error = "only http:// supported";
        return r;
    }
    std::string rest = url.substr(7);
    size_t slash = rest.find('/');
    std::string hostPort = (slash == std::string::npos) ? rest : rest.substr(0, slash);
    std::string path = (slash == std::string::npos) ? "/" : rest.substr(slash);

    int port = 80;
    size_t colon = hostPort.find(':');
    std::string host;
    if (colon != std::string::npos) {
        host = hostPort.substr(0, colon);
        port = atoi(hostPort.substr(colon + 1).c_str());
    } else {
        host = hostPort;
    }

    // Resolve
    struct hostent* he = gethostbyname(host.c_str());
    if (!he) { r.error = "DNS resolve failed"; return r; }
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    memcpy(&addr.sin_addr, he->h_addr_list[0], he->h_length);

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) { r.error = "socket failed"; return r; }

    // Non-blocking connect with timeout
    fcntl(sock, F_SETFL, O_NONBLOCK);
    int rc = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    if (rc < 0 && errno != EINPROGRESS) {
        close(sock);
        r.error = "connect failed";
        return r;
    }

    fd_set wfds;
    FD_ZERO(&wfds);
    FD_SET(sock, &wfds);
    struct timeval tv;
    tv.tv_sec = timeoutMs / 1000;
    tv.tv_usec = (timeoutMs % 1000) * 1000;
    rc = select(sock + 1, nullptr, &wfds, nullptr, &tv);
    if (rc <= 0) {
        close(sock);
        r.error = "connect timeout";
        return r;
    }

    // Back to blocking
    fcntl(sock, F_SETFL, 0);

    // Build request
    std::ostringstream req;
    req << "GET " << path << " HTTP/1.1\r\n";
    req << "Host: " << host << "\r\n";
    req << "User-Agent: Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/153.0.0.0 Mobile Safari/537.36\r\n";
    if (!referer.empty()) req << "Referer: " << referer << "\r\n";
    req << "Connection: close\r\n\r\n";
    std::string reqStr = req.str();

    send(sock, reqStr.c_str(), reqStr.size(), 0);

    // Read response
    std::string resp;
    char buf[4096];
    while (true) {
        ssize_t n = recv(sock, buf, sizeof(buf), 0);
        if (n <= 0) break;
        resp.append(buf, n);
    }
    close(sock);

    // Parse status
    size_t sp = resp.find(' ');
    if (sp != std::string::npos) {
        r.code = atoi(resp.c_str() + sp + 1);
    }
    // Find body (after \r\n\r\n)
    size_t bodyStart = resp.find("\r\n\r\n");
    if (bodyStart != std::string::npos) {
        r.body = resp.substr(bodyStart + 4);
    }
    return r;
}

bool tcpPing(const std::string& host, int port, int timeoutMs) {
    struct hostent* he = gethostbyname(host.c_str());
    if (!he) return false;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    memcpy(&addr.sin_addr, he->h_addr_list[0], he->h_length);

    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return false;
    fcntl(sock, F_SETFL, O_NONBLOCK);
    int rc = connect(sock, (struct sockaddr*)&addr, sizeof(addr));
    if (rc < 0 && errno != EINPROGRESS) { close(sock); return false; }
    fd_set wfds; FD_ZERO(&wfds); FD_SET(sock, &wfds);
    struct timeval tv; tv.tv_sec = timeoutMs/1000; tv.tv_usec = (timeoutMs%1000)*1000;
    rc = select(sock+1, nullptr, &wfds, nullptr, &tv);
    close(sock);
    return rc > 0;
}

std::string resolveHost(const std::string& host) {
    struct hostent* he = gethostbyname(host.c_str());
    if (!he) return "";
    return inet_ntoa(*((struct in_addr*)he->h_addr_list[0]));
}

} // namespace lumina
