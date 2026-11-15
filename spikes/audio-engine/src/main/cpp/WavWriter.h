// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <string>

namespace croonvox::spike {

// Writes interleaved float samples as a 16-bit PCM WAV file. Returns false on I/O error.
bool writeWav16(const std::string &path, const float *interleaved, int64_t frames, int channels,
                int sampleRate);

}  // namespace croonvox::spike
