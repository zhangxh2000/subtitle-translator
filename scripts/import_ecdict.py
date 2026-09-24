#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ECDICT → dictionary.db 词典数据构建工具

把开源的 ECDICT（https://github.com/skywind3000/ECDICT，MIT License）
转换为 App 打包使用的 SQLite 词典数据库，输出到 app/src/main/assets/dictionary.db。

用法:
    # 自动下载源数据（约 68MB）并构建
    python3 scripts/import_ecdict.py --download

    # 使用本地已下载的源数据
    python3 scripts/import_ecdict.py --csv /path/to/ecdict.csv --lemma /path/to/lemma.en.txt

    # 额外纳入罕见词（条目数与体积约翻倍，字幕覆盖率提升有限）
    python3 scripts/import_ecdict.py --download --include-rare

裁剪策略:
    ECDICT 全量 77 万条，绝大部分是极罕见词、短语和专有名词，对字幕场景没有价值。
    默认只保留「字幕里真正常见」的词:
      有词频排名(bnc <= BNC_FRQ_THRESHOLD 或 frq <= FRQ_THRESHOLD)
      或 有考试标签(tag)  或 有柯林斯星级(collins)  或 是牛津核心词(oxford)
    实测保留 59,137 条，SQLite 体积约 16.5MB。

词形还原:
    word_form 表记录「变形 → 词元」映射(如 went → go、wolves → wolf)，
    数据来自 lemma.en.txt 与 ECDICT 的 exchange 字段，两者互补。
    查询时精确匹配失败后会回退到该表，让屈折形式也能查到释义。
"""

import argparse
import csv
import os
import re
import sqlite3
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

#: ECDICT 源数据地址（按顺序尝试，前一个失败则用下一个，便于国内网络访问）
ECDICT_CSV_URLS = (
    "https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv",
    "https://gh-proxy.com/https://raw.githubusercontent.com/skywind3000/ECDICT/master/ecdict.csv",
)
ECDICT_LEMMA_URLS = (
    "https://raw.githubusercontent.com/skywind3000/ECDICT/master/lemma.en.txt",
    "https://cdn.jsdelivr.net/gh/skywind3000/ECDICT@master/lemma.en.txt",
)

#: 词频阈值：bnc(英国国家语料库) / frq(当代语料库) 排名在此范围内视为常见词
BNC_FRQ_THRESHOLD = 50000

#: 词典语言代码，写入 lang 列；多语言扩展时新增构建流程即可
DEFAULT_LANG = "en"

#: 输出路径（相对仓库根目录）
DEFAULT_OUTPUT = "app/src/main/assets/dictionary.db"

#: schema 版本，变更表结构时递增，App 端据此判断是否需要重新拷贝 assets
SCHEMA_VERSION = 1

#: 纯字母单词（含撇号/连字符），用于 --include-rare 的筛选
ALPHA_WORD_RE = re.compile(r"^[a-z][a-z'\-]*$")

#: 建立连接与单次读取的超时（秒）
CONNECT_TIMEOUT = 60
#: 低于该速度（字节/秒）视为镜像不可用，换下一个源
MIN_DOWNLOAD_RATE = 100 * 1024
#: 下载满这么多秒后开始判断速度，避免刚连接就被误判
RATE_CHECK_SECONDS = 20

#: ECDICT 的 exchange 字段 key → 中文说明
EXCHANGE_KEYS = {
    "p": "过去式",
    "d": "过去分词",
    "i": "现在分词",
    "3": "第三人称单数",
    "s": "复数",
    "r": "比较级",
    "t": "最高级",
    "0": "词元",
    "1": "词元变体",
}

SCHEMA_SQL = """
CREATE TABLE dictionary (
    id          INTEGER PRIMARY KEY,
    lang        TEXT    NOT NULL,
    word        TEXT    NOT NULL,
    phonetic    TEXT,
    translation TEXT,
    definition  TEXT,
    collins     INTEGER NOT NULL DEFAULT 0,
    oxford      INTEGER NOT NULL DEFAULT 0,
    tag         TEXT,
    bnc         INTEGER NOT NULL DEFAULT 0,
    frq         INTEGER NOT NULL DEFAULT 0,
    exchange    TEXT
);

CREATE TABLE word_form (
    lang   TEXT NOT NULL,
    form   TEXT NOT NULL,
    lemma  TEXT NOT NULL,
    source TEXT NOT NULL,
    PRIMARY KEY (lang, form, lemma)
);

CREATE TABLE metadata (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""

INDEX_SQL = """
CREATE UNIQUE INDEX idx_dictionary_lang_word ON dictionary(lang, word);
CREATE INDEX idx_dictionary_lang_frq ON dictionary(lang, frq);
CREATE INDEX idx_word_form_lang_form ON word_form(lang, form);
"""


# ---------------------------------------------------------------------------
# 工具函数
# ---------------------------------------------------------------------------

def log(msg: str) -> None:
    print(msg, flush=True)


def parse_int(value) -> int:
    """把 CSV 里的数字字段转成 int，空值/非法值一律当 0。"""
    if value is None:
        return 0
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return 0


def normalize_text(value) -> str:
    """
    规范化 ECDICT 文本字段。

    ECDICT 把多行释义存成**字面的反斜杠 n**（两个字符），而不是真正的换行符。
    这里统一转成真换行，否则 App 端会把 "\\n" 原样显示到界面上。
    """
    if not value:
        return ""
    text = str(value)
    text = text.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n")
    # 清理行首尾空白，丢掉空行
    lines = [line.strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line)


class DownloadTooSlow(Exception):
    """镜像能连上但速度过慢，应当换下一个"""


def download(urls, dest: Path, label: str) -> Path:
    """按顺序尝试多个镜像下载，已存在且非空则跳过。"""
    if dest.exists() and dest.stat().st_size > 0:
        log(f"  已存在，跳过下载: {dest} ({dest.stat().st_size / 1024 / 1024:.1f} MB)")
        return dest

    dest.parent.mkdir(parents=True, exist_ok=True)
    last_error = None
    for url in urls:
        tmp = dest.with_suffix(dest.suffix + ".part")
        try:
            log(f"  下载 {label}: {url}")
            started = time.time()
            received = 0
            with urllib.request.urlopen(url, timeout=CONNECT_TIMEOUT) as resp, open(tmp, "wb") as out:
                while True:
                    chunk = resp.read(1 << 20)
                    if not chunk:
                        break
                    out.write(chunk)
                    received += len(chunk)
                    # 部分镜像能连上但只有几十 KB/s，慢到不可用；这种情况要主动换源，
                    # 否则会一直卡在第一个镜像上（等它跑完 66MB 可能要一小时）
                    elapsed = time.time() - started
                    if elapsed >= RATE_CHECK_SECONDS:
                        rate = received / elapsed
                        if rate < MIN_DOWNLOAD_RATE:
                            raise DownloadTooSlow(f"速度仅 {rate / 1024:.0f} KB/s")

            if received == 0:
                raise DownloadTooSlow("没有收到数据")

            size_mb = tmp.stat().st_size / 1024 / 1024
            rate_kb = received / max(time.time() - started, 0.001) / 1024
            log(f"    完成 {size_mb:.1f} MB，平均 {rate_kb:.0f} KB/s，耗时 {time.time() - started:.0f}s")
            tmp.replace(dest)
            return dest
        except (urllib.error.URLError, OSError, TimeoutError, DownloadTooSlow) as exc:
            last_error = exc
            log(f"    放弃该源: {exc}")
            tmp.unlink(missing_ok=True)

    raise SystemExit(f"下载 {label} 失败，请手动下载后用 --csv/--lemma 指定路径。最后错误: {last_error}")


# ---------------------------------------------------------------------------
# 裁剪策略
# ---------------------------------------------------------------------------

def is_common_word(row: dict) -> bool:
    """判断一条 ECDICT 记录是否属于「常见词」，即默认保留的集合。"""
    bnc = parse_int(row.get("bnc"))
    frq = parse_int(row.get("frq"))
    if (0 < bnc <= BNC_FRQ_THRESHOLD) or (0 < frq <= BNC_FRQ_THRESHOLD):
        return True
    if str(row.get("tag") or "").strip():
        return True
    if parse_int(row.get("collins")) > 0:
        return True
    if str(row.get("oxford") or "").strip() == "1":
        return True
    return False


def is_rare_but_real_word(row: dict, word: str) -> bool:
    """
    --include-rare 额外纳入的词：没有词频排名，但确实是可变化的真实单词。

    条件：纯字母单词 + 有中文释义 + 有词形变化(exchange，说明是可屈折的实词)。
    这类词在字幕里出现频率很低，但能提升长尾覆盖率。
    """
    if not ALPHA_WORD_RE.match(word):
        return False
    if not normalize_text(row.get("translation")):
        return False
    return bool(str(row.get("exchange") or "").strip())


# ---------------------------------------------------------------------------
# 构建流程
# ---------------------------------------------------------------------------

def read_dictionary(csv_path: Path, include_rare: bool, lang: str):
    """流式读取 ECDICT CSV，返回保留的条目列表与统计信息。"""
    kept = {}
    stats = {
        "total": 0,
        "common": 0,
        "rare": 0,
        "collision": 0,
        "no_translation": 0,
    }

    with open(csv_path, encoding="utf-8", newline="") as fp:
        reader = csv.DictReader(fp)
        for row in reader:
            stats["total"] += 1
            raw_word = str(row.get("word") or "").strip()
            if not raw_word:
                continue

            word = raw_word.lower()
            common = is_common_word(row)
            rare = (not common) and include_rare and is_rare_but_real_word(row, word)
            if not (common or rare):
                continue

            translation = normalize_text(row.get("translation"))
            if not translation:
                # 没有中文释义的条目对用户没有价值
                stats["no_translation"] += 1
                continue

            entry = {
                "lang": lang,
                "word": word,
                "phonetic": normalize_text(row.get("phonetic")),
                "translation": translation,
                "definition": normalize_text(row.get("definition")),
                "collins": parse_int(row.get("collins")),
                "oxford": 1 if str(row.get("oxford") or "").strip() == "1" else 0,
                "tag": " ".join(str(row.get("tag") or "").split()),
                "bnc": parse_int(row.get("bnc")),
                "frq": parse_int(row.get("frq")),
                "exchange": str(row.get("exchange") or "").strip(),
            }

            if word in kept:
                # 理论上 ECDICT 已去重；万一出现大小写碰撞，保留信息更全的一条
                stats["collision"] += 1
                if _richness(entry) <= _richness(kept[word]):
                    continue

            entry["_common"] = common
            kept[word] = entry

    entries = list(kept.values())
    # 统计基于最终保留的条目，避免碰撞替换时重复计数
    stats["common"] = sum(1 for e in entries if e["_common"])
    stats["rare"] = len(entries) - stats["common"]
    return entries, stats


def _richness(entry: dict) -> tuple:
    """衡量条目信息丰富度，用于碰撞时择优。"""
    return (
        1 if entry["bnc"] or entry["frq"] else 0,
        entry["collins"],
        len(entry["translation"]),
        len(entry["definition"]),
    )


def read_lemma_forms(lemma_path: Path) -> dict:
    """
    解析 lemma.en.txt，返回 {变形: {词元, ...}}。

    文件格式（以 ; 开头的行是注释）:
        词元/出现次数 -> 变形1,变形2,变形3
    """
    forms = {}
    if not lemma_path or not lemma_path.exists():
        log(f"  警告: 未找到 {lemma_path}，将只用 exchange 字段构建词形表")
        return forms

    with open(lemma_path, encoding="utf-8") as fp:
        for line in fp:
            line = line.strip()
            if not line or line.startswith(";") or "->" not in line:
                continue

            head, form_part = line.split("->", 1)
            head = head.strip()
            # head 形如 "word/123"；词元本身可能含 '/'，所以从右边切一次
            head_parts = head.rsplit("/", 1)
            if len(head_parts) == 2 and head_parts[1].strip().isdigit():
                lemma = head_parts[0]
            else:
                lemma = head
            lemma = lemma.strip().lower()
            if not lemma:
                continue

            for form in form_part.split(","):
                form = form.strip().lower()
                if form and form != lemma:
                    forms.setdefault(form, set()).add(lemma)

    return forms


def read_exchange_forms(entries) -> dict:
    """从词典条目的 exchange 字段反向提取「变形 → 词元」映射，补充 lemma 文件缺漏。"""
    forms = {}
    for entry in entries:
        exchange = entry["exchange"]
        if not exchange:
            continue
        for pair in exchange.split("/"):
            if ":" not in pair:
                continue
            key, values = pair.split(":", 1)
            if key not in EXCHANGE_KEYS or key in ("0", "1"):
                continue
            for value in values.split(","):
                value = value.strip().lower()
                if value and value != entry["word"]:
                    forms.setdefault(value, set()).add(entry["word"])
    return forms


def build_word_forms(entries, lemma_forms: dict, exchange_forms: dict, lang: str):
    """
    合并两个来源的词形映射，只保留词元确实存在于词典中的记录。

    词典中存在的词元优先：例如 better 既是独立词条、又是 good 的比较级，
    查询 better 时精确匹配本来就命中，词形表只用于精确匹配失败的场景。
    """
    known_words = {entry["word"] for entry in entries}
    rows = []
    seen = set()

    for form, lemmas in lemma_forms.items():
        for lemma in lemmas:
            if lemma in known_words and (form, lemma) not in seen:
                seen.add((form, lemma))
                rows.append((lang, form, lemma, "lemma"))

    for form, lemmas in exchange_forms.items():
        for lemma in lemmas:
            if lemma in known_words and (form, lemma) not in seen:
                seen.add((form, lemma))
                rows.append((lang, form, lemma, "exchange"))

    return rows


def build_database(output: Path, entries, form_rows, csv_name: str) -> None:
    """写入 SQLite 数据库。"""
    output.parent.mkdir(parents=True, exist_ok=True)
    tmp_output = output.with_suffix(".db.tmp")
    tmp_output.unlink(missing_ok=True)

    conn = sqlite3.connect(tmp_output)
    try:
        # 体积优化：分页大小 4KB、关闭 journal 副本（构建期一次性写入）
        conn.executescript("PRAGMA page_size = 4096; PRAGMA journal_mode = OFF; PRAGMA synchronous = OFF;")
        conn.executescript(SCHEMA_SQL)

        conn.executemany(
            """INSERT INTO dictionary
               (lang, word, phonetic, translation, definition, collins, oxford, tag, bnc, frq, exchange)
               VALUES (:lang, :word, :phonetic, :translation, :definition,
                       :collins, :oxford, :tag, :bnc, :frq, :exchange)""",
            entries,
        )
        conn.executemany(
            "INSERT OR IGNORE INTO word_form (lang, form, lemma, source) VALUES (?, ?, ?, ?)",
            form_rows,
        )

        conn.executemany(
            "INSERT OR REPLACE INTO metadata (key, value) VALUES (?, ?)",
            [
                ("schema_version", str(SCHEMA_VERSION)),
                ("lang", DEFAULT_LANG),
                ("source", "ECDICT"),
                ("source_url", "https://github.com/skywind3000/ECDICT"),
                ("source_license", "MIT License, Copyright (c) Linwei"),
                ("source_file", csv_name),
                ("entry_count", str(len(entries))),
                ("form_count", str(len(form_rows))),
                ("built_at", time.strftime("%Y-%m-%dT%H:%M:%S")),
                ("generator", "scripts/import_ecdict.py"),
            ],
        )

        conn.executescript(INDEX_SQL)
        conn.commit()
        conn.execute("ANALYZE")
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    # 原子替换，避免留下半成品
    tmp_output.replace(output)


def verify(output: Path) -> None:
    """对产物做一次冒烟校验，确保 App 端拿到的是可用数据。"""
    conn = sqlite3.connect(output)
    try:
        entry_count = conn.execute("SELECT COUNT(*) FROM dictionary").fetchone()[0]
        form_count = conn.execute("SELECT COUNT(*) FROM word_form").fetchone()[0]
        if entry_count == 0:
            raise SystemExit("校验失败: dictionary 表为空")

        # 抽查几个典型词：必须查得到且释义非空
        for word in ("run", "inevitable", "serendipity", "happy"):
            row = conn.execute(
                "SELECT translation FROM dictionary WHERE lang = ? AND word = ?", (DEFAULT_LANG, word)
            ).fetchone()
            if not row or not row[0]:
                raise SystemExit(f"校验失败: 常见词 {word} 缺失或释义为空")

        # 不应存在字面 \n（反斜杠 + n 两个字符），否则 UI 会显示成 "\n"
        # 注意：这里不能用 LIKE '%\n%'，SQLite 的 LIKE 会把 \n 当作转义的字母 n
        bad = conn.execute(
            "SELECT COUNT(*) FROM dictionary WHERE instr(translation, char(92) || 'n') > 0"
        ).fetchone()[0]
        if bad:
            raise SystemExit(f"校验失败: {bad} 条释义残留字面 \\n")

        # 词形还原抽查
        for form, lemma in (("went", "go"), ("wolves", "wolf"), ("happier", "happy")):
            row = conn.execute(
                "SELECT 1 FROM word_form WHERE lang = ? AND form = ? AND lemma = ?",
                (DEFAULT_LANG, form, lemma),
            ).fetchone()
            if not row:
                raise SystemExit(f"校验失败: 词形映射 {form} → {lemma} 缺失")

        log(f"  校验通过: {entry_count} 条词条, {form_count} 条词形映射")
    finally:
        conn.close()


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent

    parser = argparse.ArgumentParser(
        description="把 ECDICT 转换为 App 使用的 SQLite 词典 (app/src/main/assets/dictionary.db)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--csv", type=Path, help="本地 ecdict.csv 路径")
    parser.add_argument("--lemma", type=Path, help="本地 lemma.en.txt 路径")
    parser.add_argument("--out", type=Path, default=repo_root / DEFAULT_OUTPUT, help=f"输出路径 (默认 {DEFAULT_OUTPUT})")
    parser.add_argument("--download", action="store_true", help="自动下载源数据到 .cache/ 目录")
    parser.add_argument("--include-rare", action="store_true", help="额外纳入罕见词（体积约翻倍）")
    parser.add_argument("--skip-verify", action="store_true", help="跳过产物校验")
    args = parser.parse_args()

    cache_dir = repo_root / ".cache" / "ecdict"
    csv_path = args.csv
    lemma_path = args.lemma

    if args.download:
        log("下载 ECDICT 源数据...")
        csv_path = csv_path or download(ECDICT_CSV_URLS, cache_dir / "ecdict.csv", "ecdict.csv")
        lemma_path = lemma_path or download(ECDICT_LEMMA_URLS, cache_dir / "lemma.en.txt", "lemma.en.txt")

    if not csv_path or not csv_path.exists():
        parser.error("缺少 ecdict.csv，请用 --csv 指定路径，或加 --download 自动下载")

    started = time.time()
    log(f"读取 {csv_path} ...")
    entries, stats = read_dictionary(csv_path, args.include_rare, DEFAULT_LANG)
    log(
        f"  扫描 {stats['total']} 条 → 保留 {len(entries)} 条"
        f"（常见词 {stats['common']}，罕见词 {stats['rare']}；"
        f"缺中文释义丢弃 {stats['no_translation']}，大小写碰撞 {stats['collision']}）"
    )

    log("构建词形还原表 ...")
    lemma_forms = read_lemma_forms(lemma_path) if lemma_path else {}
    exchange_forms = read_exchange_forms(entries)
    form_rows = build_word_forms(entries, lemma_forms, exchange_forms, DEFAULT_LANG)
    log(
        f"  变形映射 {len(form_rows)} 条"
        f"（lemma 文件 {len(lemma_forms)} 个变形 / exchange 字段 {len(exchange_forms)} 个变形）"
    )

    log(f"写入 {args.out} ...")
    build_database(args.out, entries, form_rows, csv_path.name)
    size_mb = args.out.stat().st_size / 1024 / 1024
    log(f"  完成: {size_mb:.1f} MB")

    if not args.skip_verify:
        log("校验产物 ...")
        verify(args.out)

    log(f"全部完成，耗时 {time.time() - started:.1f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
