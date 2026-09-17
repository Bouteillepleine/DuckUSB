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

#include "Logger.h"

using Elf_Ehdr = ElfW(Ehdr);
using Elf_Shdr = ElfW(Shdr);
using Elf_Sym = ElfW(Sym);
using Elf_Phdr = ElfW(Phdr);

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

void ElfImg::parse() {
    auto *header = reinterpret_cast<Elf_Ehdr *>(elf_);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0) return;
    header_ = header;

    auto *sections = reinterpret_cast<Elf_Shdr *>(elf_ + header->e_shoff);
    auto *section_names = elf_ + sections[header->e_shstrndx].sh_offset;

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

    for (int i = 0; i < header->e_shnum; i++) {
        auto &section = sections[i];
        if (section.sh_type != SHT_SYMTAB && section.sh_type != SHT_DYNSYM) continue;
        if (section.sh_entsize == 0) continue;

        auto *symbols = reinterpret_cast<Elf_Sym *>(elf_ + section.sh_offset);
        auto count = section.sh_size / section.sh_entsize;
        auto *strings = elf_ + sections[section.sh_link].sh_offset;

        for (size_t s = 0; s < count; s++) {
            auto &symbol = symbols[s];
            if (symbol.st_name == 0 || symbol.st_value == 0) continue;
            std::string_view name(strings + symbol.st_name);
            symbols_.emplace(name, static_cast<uintptr_t>(symbol.st_value));
        }
    }
    LOGD("ElfImg: %s parsed, %zu symbols, bias %p",
         name_.c_str(), symbols_.size(), reinterpret_cast<void *>(bias_));
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
