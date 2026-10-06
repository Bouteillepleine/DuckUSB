#pragma once

#include <sys/types.h>

#include <cstdint>
#include <deque>
#include <string>
#include <string_view>
#include <unordered_map>
#include <vector>

class ElfImg {
public:
    explicit ElfImg(std::string_view base_name);

    ~ElfImg();

    bool valid() const { return base_ != nullptr && parsed_; }

    void *symbol(std::string_view name) const;

    void *symbolWithPrefix(std::string_view prefix) const;

private:
    bool resolveLoaded();

    bool resolveFromMaps();

    void parse();

    void release();

    void collect(const char *image);

    std::string name_;
    std::string path_;
    void *base_ = nullptr;
    char *elf_ = nullptr;
    off_t size_ = 0;
    uintptr_t bias_ = static_cast<uintptr_t>(-1);
    bool parsed_ = false;
    std::deque<std::string> owned_;
    std::unordered_map<std::string_view, uintptr_t> symbols_;
};
