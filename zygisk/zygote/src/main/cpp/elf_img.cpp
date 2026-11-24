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
#include <map>
#include <mutex>
#include <vector>

extern "C" {
#include <xz.h>
}

#include "Logger.h"

using Elf_Ehdr = ElfW(Ehdr);
using Elf_Shdr = ElfW(Shdr);
using Elf_Sym = ElfW(Sym);
using Elf_Phdr = ElfW(Phdr);

namespace {

constexpr size_t kInitialOut = 4u << 20;
constexpr size_t kMaxOut = 64u << 20;

bool fits(size_t size, uint64_t offset, uint64_t length) {
    return offset <= size && length <= size - offset;
}

std::vector<uint8_t> inflate(const uint8_t *in, size_t in_size) {
    static std::once_flag crc_once;
    std::call_once(crc_once, [] {
        xz_crc32_init();
        xz_crc64_init();
    });
    for (size_t cap = kInitialOut; cap <= kMaxOut; cap *= 4) {
        std::vector<uint8_t> out(cap);
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
        if (ret != XZ_BUF_ERROR) {
            LOGD("gnu_debugdata: xz failed with %d", ret);
            return {};
        }
    }
    LOGD("gnu_debugdata: larger than %zu bytes, skipped", kMaxOut);
    return {};
}

const char *basename_of(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash != nullptr ? slash + 1 : path;
}

struct LoadedQuery {
    const char *name;
    uintptr_t bias;
    uintptr_t base;
    std::string path;
    bool found;
};

int match_loaded(dl_phdr_info *info, size_t, void *data) {
    auto *query = static_cast<LoadedQuery *>(data);
    if (info->dlpi_name == nullptr || info->dlpi_name[0] == '\0') return 0;
    if (strcmp(basename_of(info->dlpi_name), query->name) != 0) return 0;

    uintptr_t first = static_cast<uintptr_t>(-1);
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const auto &program = info->dlpi_phdr[i];
        if (program.p_type == PT_LOAD && program.p_vaddr < first) first = program.p_vaddr;
    }
    if (first == static_cast<uintptr_t>(-1)) return 0;

    query->bias = static_cast<uintptr_t>(info->dlpi_addr);
    query->base = query->bias + first;
    query->path = info->dlpi_name;
    query->found = true;
    return 1;
}

struct MapGroup {
    uintptr_t lowest = static_cast<uintptr_t>(-1);
    bool writable = false;
    std::string path;
};

}  // namespace

// The loaded library, never a plain read-only mapping of the same file: other
// modules leave whole-file parse mappings of libart.so behind, and adopting one
// as the load base sends every resolved symbol into dead memory.
bool ElfImg::resolveLoaded() {
    LoadedQuery query{name_.c_str(), 0, 0, {}, false};
    dl_iterate_phdr(match_loaded, &query);
    if (!query.found) return false;
    bias_ = query.bias;
    base_ = reinterpret_cast<void *>(query.base);
    path_ = std::move(query.path);
    return true;
}

bool ElfImg::resolveFromMaps() {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (maps == nullptr) return false;

    // A mapping group is keyed by the load base it implies; only a loaded
    // library ever carries a writable segment.
    std::map<uintptr_t, MapGroup> groups;
    char line[1024];
    while (fgets(line, sizeof(line), maps) != nullptr) {
        char *path = strchr(line, '/');
        if (path == nullptr) continue;
        char *newline = strchr(path, '\n');
        if (newline != nullptr) *newline = '\0';
        if (strcmp(basename_of(path), name_.c_str()) != 0) continue;

        uintptr_t start = strtoul(line, nullptr, 16);
        char *cursor = strchr(line, ' ');
        if (cursor == nullptr || strlen(cursor + 1) < 4) continue;
        const char *perms = cursor + 1;
        uintptr_t offset = strtoul(perms + 5, nullptr, 16);
        if (offset > start) continue;

        auto &group = groups[start - offset];
        if (start < group.lowest) group.lowest = start;
        if (perms[1] == 'w') group.writable = true;
        if (group.path.empty()) group.path = path;
    }
    fclose(maps);

    for (const auto &[base, group]: groups) {
        if (!group.writable) continue;
        base_ = reinterpret_cast<void *>(base);
        path_ = group.path;
        return true;
    }
    return false;
}

ElfImg::ElfImg(std::string_view base_name) : name_(base_name) {
    if (!resolveLoaded() && !resolveFromMaps()) {
        LOGD("ElfImg: %s not loaded", name_.c_str());
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
    release();
}

void ElfImg::collect(const char *image, size_t size) {
    if (size < sizeof(Elf_Ehdr)) return;
    auto *header = reinterpret_cast<const Elf_Ehdr *>(image);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0) return;
    if (header->e_shentsize != sizeof(Elf_Shdr)) return;
    if (!fits(size, header->e_shoff, uint64_t(header->e_shnum) * sizeof(Elf_Shdr))) return;

    auto *sections = reinterpret_cast<const Elf_Shdr *>(image + header->e_shoff);
    for (int i = 0; i < header->e_shnum; i++) {
        const auto &section = sections[i];
        if (section.sh_type != SHT_SYMTAB && section.sh_type != SHT_DYNSYM) continue;
        if (section.sh_entsize < sizeof(Elf_Sym)) continue;
        if (!fits(size, section.sh_offset, section.sh_size)) continue;
        if (section.sh_link >= header->e_shnum) continue;
        const auto &strtab = sections[section.sh_link];
        if (!fits(size, strtab.sh_offset, strtab.sh_size)) continue;

        const char *strings = image + strtab.sh_offset;
        size_t count = section.sh_size / section.sh_entsize;
        for (size_t s = 0; s < count; s++) {
            auto *symbol = reinterpret_cast<const Elf_Sym *>(
                    image + section.sh_offset + s * section.sh_entsize);
            if (symbol->st_name == 0 || symbol->st_value == 0) continue;
            if (symbol->st_name >= strtab.sh_size) continue;
            size_t room = strtab.sh_size - symbol->st_name;
            size_t length = strnlen(strings + symbol->st_name, room);
            if (length == room) continue;
            owned_.emplace_back(strings + symbol->st_name, length);
            symbols_.emplace(owned_.back(), static_cast<uintptr_t>(symbol->st_value));
        }
    }
}

void ElfImg::parse() {
    const auto size = static_cast<size_t>(size_);
    if (size < sizeof(Elf_Ehdr)) return;
    auto *header = reinterpret_cast<Elf_Ehdr *>(elf_);
    if (memcmp(header->e_ident, ELFMAG, SELFMAG) != 0) return;

    if (bias_ == static_cast<uintptr_t>(-1)) {
        if (header->e_phentsize == sizeof(Elf_Phdr) &&
            fits(size, header->e_phoff, uint64_t(header->e_phnum) * sizeof(Elf_Phdr))) {
            for (int i = 0; i < header->e_phnum; i++) {
                auto *program = reinterpret_cast<Elf_Phdr *>(
                        elf_ + header->e_phoff + sizeof(Elf_Phdr) * i);
                if (program->p_type == PT_LOAD && program->p_offset == 0) {
                    bias_ = reinterpret_cast<uintptr_t>(base_) - program->p_vaddr;
                    break;
                }
            }
        }
        if (bias_ == static_cast<uintptr_t>(-1)) {
            bias_ = reinterpret_cast<uintptr_t>(base_);
        }
    }

    collect(elf_, size);

    static constexpr char kDebugData[] = ".gnu_debugdata";
    if (header->e_shentsize == sizeof(Elf_Shdr) &&
        fits(size, header->e_shoff, uint64_t(header->e_shnum) * sizeof(Elf_Shdr)) &&
        header->e_shstrndx < header->e_shnum) {
        auto *sections = reinterpret_cast<Elf_Shdr *>(elf_ + header->e_shoff);
        const auto &names = sections[header->e_shstrndx];
        if (fits(size, names.sh_offset, names.sh_size)) {
            const char *section_names = elf_ + names.sh_offset;
            for (int i = 0; i < header->e_shnum; i++) {
                const auto &section = sections[i];
                if (section.sh_name >= names.sh_size) continue;
                if (names.sh_size - section.sh_name < sizeof(kDebugData)) continue;
                if (memcmp(section_names + section.sh_name, kDebugData, sizeof(kDebugData)) != 0) continue;
                if (!fits(size, section.sh_offset, section.sh_size)) break;
                auto data = inflate(reinterpret_cast<const uint8_t *>(elf_ + section.sh_offset),
                                    section.sh_size);
                if (!data.empty()) collect(reinterpret_cast<const char *>(data.data()), data.size());
                break;
            }
        }
    }

    parsed_ = true;
    LOGD("ElfImg: %s parsed, %zu symbols, bias %p",
         name_.c_str(), symbols_.size(), reinterpret_cast<void *>(bias_));
}

// Hand the whole-file mapping back: leaving it around is what misleads every
// other module that looks libart.so up in /proc/self/maps.
void ElfImg::release() {
    if (elf_ == nullptr) return;
    munmap(elf_, size_);
    elf_ = nullptr;
    size_ = 0;
}

void *ElfImg::symbol(std::string_view name) const {
    if (!valid()) return nullptr;
    auto it = symbols_.find(name);
    if (it == symbols_.end()) return nullptr;
    return reinterpret_cast<void *>(bias_ + it->second);
}

void *ElfImg::symbolWithPrefix(std::string_view prefix) const {
    if (!valid()) return nullptr;
    const std::pair<const std::string_view, uintptr_t> *best = nullptr;
    for (const auto &entry: symbols_) {
        if (entry.first.size() < prefix.size()) continue;
        if (entry.first.compare(0, prefix.size(), prefix) != 0) continue;
        if (best == nullptr || entry.first < best->first) best = &entry;
    }
    return best == nullptr ? nullptr : reinterpret_cast<void *>(bias_ + best->second);
}

ElfImg::~ElfImg() {
    release();
}
