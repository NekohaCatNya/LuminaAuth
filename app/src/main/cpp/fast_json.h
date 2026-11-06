#pragma once
#include <string>

namespace lumina {

struct LoginResponse {
    int result;
    std::string msg;
    bool valid;
};

// 快速从 drcom 风格 JSONP 响应中提取 result 和 msg，无需完整 JSON 解析器
LoginResponse parseDrcomResponse(const std::string& body);

}
