#include "log_ring_buffer.h"

namespace lumina {

LogRingBuffer::LogRingBuffer(size_t capacity)
    : m_capacity(capacity), m_head(0), m_count(0) {
    m_buf.resize(capacity);
}

void LogRingBuffer::push(const std::string& line) {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_buf[m_head] = line;
    m_head = (m_head + 1) % m_capacity;
    if (m_count < m_capacity) m_count++;
}

std::string LogRingBuffer::getAllText() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    std::string out;
    out.reserve(m_count * 128);
    // 从最老的开始输出
    size_t start = (m_count == m_capacity) ? m_head : 0;
    for (size_t i = 0; i < m_count; i++) {
        size_t idx = (start + i) % m_capacity;
        out += m_buf[idx];
        out += '\n';
    }
    return out;
}

void LogRingBuffer::clear() {
    std::lock_guard<std::mutex> lock(m_mutex);
    m_head = 0;
    m_count = 0;
}

size_t LogRingBuffer::size() const {
    std::lock_guard<std::mutex> lock(m_mutex);
    return m_count;
}

}
