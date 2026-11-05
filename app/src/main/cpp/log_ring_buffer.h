#pragma once
#include <string>
#include <vector>
#include <mutex>

namespace lumina {

class LogRingBuffer {
public:
    LogRingBuffer(size_t capacity);

    void push(const std::string& line);
    std::string getAllText() const;
    void clear();
    size_t size() const;

private:
    size_t m_capacity;
    size_t m_head;     // 下一个写入位置
    size_t m_count;    // 当前元素数
    std::vector<std::string> m_buf;
    mutable std::mutex m_mutex;
};

}
