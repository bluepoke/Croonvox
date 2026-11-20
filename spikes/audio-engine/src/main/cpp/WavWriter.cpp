// SPDX-License-Identifier: GPL-3.0-or-later
#include "WavWriter.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

namespace meavox::spike {

namespace {

void put16(std::vector<uint8_t> &b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xff));
    b.push_back(static_cast<uint8_t>(v >> 8));
}

void put32(std::vector<uint8_t> &b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>((v >> (8 * i)) & 0xff));
}

void putTag(std::vector<uint8_t> &b, const char *tag) { b.insert(b.end(), tag, tag + 4); }

}  // namespace

bool writeWav16(const std::string &path, const float *interleaved, int64_t frames, int channels,
                int sampleRate) {
    const auto samples = static_cast<uint64_t>(frames) * static_cast<uint64_t>(channels);
    const uint64_t dataBytes = samples * 2;
    if (dataBytes > 0xFFFFFFFFull - 36) return false;

    std::vector<uint8_t> header;
    header.reserve(44);
    putTag(header, "RIFF");
    put32(header, static_cast<uint32_t>(36 + dataBytes));
    putTag(header, "WAVE");
    putTag(header, "fmt ");
    put32(header, 16);
    put16(header, 1);  // PCM
    put16(header, static_cast<uint16_t>(channels));
    put32(header, static_cast<uint32_t>(sampleRate));
    put32(header, static_cast<uint32_t>(sampleRate * channels * 2));
    put16(header, static_cast<uint16_t>(channels * 2));
    put16(header, 16);
    putTag(header, "data");
    put32(header, static_cast<uint32_t>(dataBytes));

    FILE *f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = std::fwrite(header.data(), 1, header.size(), f) == header.size();
    std::vector<int16_t> chunk(8192);
    for (uint64_t i = 0; ok && i < samples; i += chunk.size()) {
        const uint64_t n = std::min<uint64_t>(chunk.size(), samples - i);
        for (uint64_t k = 0; k < n; ++k) {
            const float s = std::clamp(interleaved[i + k], -1.0f, 1.0f);
            chunk[k] = static_cast<int16_t>(std::lrint(s * 32767.0f));
        }
        ok = std::fwrite(chunk.data(), sizeof(int16_t), n, f) == n;  // little-endian targets only
    }
    const bool closed = std::fclose(f) == 0;
    return ok && closed;
}

}  // namespace meavox::spike
