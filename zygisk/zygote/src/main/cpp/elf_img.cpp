#include "elf_img.h"

#include <elf.h>
#include <fcntl.h>
#include <link.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

extern "C" {
#include <xz.h>
}

#include "Logger.h"

using Elf_Ehdr = ElfW(Ehdr);
using Elf_Shdr = ElfW(Shdr);
using Elf_Sym = ElfW(Sym);
using Elf_Phdr = ElfW(Phdr);

static std::vector<uint8_t> inflate(const uint8_t *in, size_t in_size) {
    static bool crc_ready = false;
    if (!crc_ready) {
        xz_crc32_init();
        xz_crc64_init();
        crc_ready = true;
    }
    std::vector<uint8_t> out(4u << 20);
    for (int attempt = 0; attempt < 5; attempt++) {
        xz_dec *dec = xz_dec_init(XZ_SINGLE, 0);
        if (dec == nullptr) return {};
        xz_buf buf{};
        buf.in = in;
        buf.in_pos = 0;
        buf.in_size = in_size;
        buf.out = out.data();
        buf.out_pos = 0;
        buf.out_size = out.size();
        xz_ret ret = xz_dec_run(dec, &buf);
        xz_dec_end(dec);
        if (ret == XZ_STREAM_END) {
            out.resize(buf.out_pos);
            return out;
        }
        if (ret != XZ_BUF_ERROR && ret != XZ_MEMLIMIT_ERROR) {
            LOGD("gnu_debugdata: xz failed with %d", ret);
            return {};
        }
        out.assign(out.size() * 4, 0);
    }
    return {};
}

ElfImg::ElfImg(std::string_view base_name) : name_(base_name) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) return;

    char line[1024];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        if (strstr(line, name_.c_str()) == nullptr) continue;
        char *path = strchr(line, '/');
        if (path == nullptr) continue;
        char *newline = strchr(path, '\n');
        if (newline != nullptr) *newline = '\0';

        uintptr_t start = strtoul(line, nullptr, 16);
        if (base_ == nullptr) {
            base_ = reinterpret_cast<void *>(start);
            path_ = path;
        }
    }
    fclose(maps);

    if (base_ == nullptr || path_.empty()) {
        LOGD("ElfImg: %s not mapped", name_.c_str());
        return;
    }

    int fd = open(path_.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        LOGD("ElfImg: cannot open %s", path_.c_str());
        return;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0) {
        close(fd);
        return;
    }
    size_ = st.st_size;
    elf_ = static_cast<char *>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (elf_ == MAP_FAILED) {
        elf_ = nullptr;
        return;
    }

    parse();
}

void ElfImg::collect(const char *image, bool keep_strings) {
    auto *header = reinterpret_cast<const Elf_Ehdr *>(image);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0) return;

    auto *sections = reinterpret_cast<const Elf_Shdr *>(image + header->e_shoff);
    for (int i = 0; i < header->e_shnum; i++) {
        const auto &section = sections[i];
        if (section.sh_type != SHT_SYMTAB && section.sh_type != SHT_DYNSYM) continue;
        if (section.sh_entsize == 0) continue;

        auto *symbols = reinterpret_cast<const Elf_Sym *>(image + section.sh_offset);
        auto count = section.sh_size / section.sh_entsize;
        auto *strings = image + sections[section.sh_link].sh_offset;

        for (size_t s = 0; s < count; s++) {
            const auto &symbol = symbols[s];
            if (symbol.st_name == 0 || symbol.st_value == 0) continue;
            const char *raw = strings + symbol.st_name;
            if (keep_strings) {
                owned_.emplace_back(raw);
                symbols_.emplace(owned_.back(), static_cast<uintptr_t>(symbol.st_value));
            } else {
                symbols_.emplace(std::string_view(raw), static_cast<uintptr_t>(symbol.st_value));
            }
        }
    }
}

void ElfImg::parse() {
    auto *header = reinterpret_cast<Elf_Ehdr *>(elf_);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0) return;
    header_ = header;

    for (int i = 0; i < header->e_phnum; i++) {
        auto *program = reinterpret_cast<Elf_Phdr *>(
                elf_ + header->e_phoff + header->e_phentsize * i);
        if (program->p_type == PT_LOAD && program->p_offset == 0) {
            bias_ = reinterpret_cast<uintptr_t>(base_) - program->p_vaddr;
            break;
        }
    }
    if (bias_ == static_cast<uintptr_t>(-1)) {
        bias_ = reinterpret_cast<uintptr_t>(base_);
    }

    collect(elf_, false);

    auto *sections = reinterpret_cast<Elf_Shdr *>(elf_ + header->e_shoff);
    auto *section_names = elf_ + sections[header->e_shstrndx].sh_offset;
    for (int i = 0; i < header->e_shnum; i++) {
        auto &section = sections[i];
        if (strcmp(section_names + section.sh_name, ".gnu_debugdata") != 0) continue;
        auto data = inflate(reinterpret_cast<const uint8_t *>(elf_ + section.sh_offset),
                            section.sh_size);
        if (data.empty()) break;
        debug_ = std::move(data);
        collect(reinterpret_cast<const char *>(debug_.data()), true);
        break;
    }

    LOGD("ElfImg: %s parsed, %zu symbols, debugdata %zu bytes, bias %p",
         name_.c_str(), symbols_.size(), debug_.size(), reinterpret_cast<void *>(bias_));
}

void *ElfImg::symbol(std::string_view name) const {
    if (!valid()) return nullptr;
    auto it = symbols_.find(name);
    if (it == symbols_.end()) return nullptr;
    return reinterpret_cast<void *>(bias_ + it->second);
}

void *ElfImg::symbolWithPrefix(std::string_view prefix) const {
    if (!valid()) return nullptr;
    for (const auto &entry: symbols_) {
        if (entry.first.size() >= prefix.size() &&
            entry.first.compare(0, prefix.size(), prefix) == 0) {
            return reinterpret_cast<void *>(bias_ + entry.second);
        }
    }
    return nullptr;
}

ElfImg::~ElfImg() {
    if (elf_ != nullptr) munmap(elf_, size_);
}
