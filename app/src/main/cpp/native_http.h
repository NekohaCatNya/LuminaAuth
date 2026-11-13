#pragma once
#include <string>

namespace lumina {

struct HttpResult {
    int code = 0;
    std::string body;
    std::string error;
};

// Set network interface binding: "" = auto, "wlan0" = WiFi, "rmnet0" = mobile
void setBindInterface(const std::string& iface);

// HTTP GET/POST over raw sockets (no external deps).
// Only http:// is supported; https is still handled by the Java layer.
HttpResult httpRequest(const std::string& method,
                       const std::string& url,
                       const std::string& body,
                       const std::string& referer,
                       int timeoutMs);

// TCP connect check
bool tcpPing(const std::string& host, int port, int timeoutMs = 3000);

// Resolve host to IP (returns empty string on failure)
std::string resolveHost(const std::string& host);

} // namespace lumina
