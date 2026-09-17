#pragma once

#include <string>
#include <string_view>
#include <unordered_map>

class ElfImg {
public:
    explicit ElfImg(std::string_view base_name);

    ~ElfImg();

    bool valid() const { return base_ != nullptr && header_ != nullptr; }

    void *symbol(std::string_view name) const;

    void *symbolWithPrefix(std::string_view prefix) const;

private:
    void parse();

    std::string name_;
    std::string path_;
    void *base_ = nullptr;
    char *elf_ = nullptr;
    off_t size_ = 0;
    uintptr_t bias_ = static_cast<uintptr_t>(-1);
    const void *header_ = nullptr;
    std::unordered_map<std::string_view, uintptr_t> symbols_;
};
