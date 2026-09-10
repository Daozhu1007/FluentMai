"""
sync_data.py — 从水鱼 API 拉取舞萌 DX 曲库数据并存入本地 SQLite 数据库。

用法: python sync_data.py
"""

import json
import logging
import sys
from typing import Any

import requests
from fluentmai_core import database
from fluentmai_core.catalog import parse_diving_fish_music_data
from fluentmai_core.runtime_paths import database_path

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------

API_URL = "https://www.diving-fish.com/api/maimaidxprober/music_data"


def default_db_path() -> str:
    return str(database_path())


REQUEST_TIMEOUT = 30  # 秒

# 难度标签（按 ds / level 数组索引顺序）
DIFF_LABELS = ["basic", "advanced", "expert", "master", "remaster"]

logging.basicConfig(
    level=logging.INFO,
    format="[%(asctime)s] %(levelname)s - %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
logger = logging.getLogger("sync_data")


# ---------------------------------------------------------------------------
# 数据拉取
# ---------------------------------------------------------------------------

def fetch_music_data() -> list[dict[str, Any]]:
    """向水鱼 API 发送 GET 请求，返回曲库 JSON 列表。"""
    logger.info("正在请求 %s ...", API_URL)
    try:
        resp = requests.get(API_URL, timeout=REQUEST_TIMEOUT)
        resp.raise_for_status()
    except requests.RequestException as exc:
        logger.error("网络请求失败: %s", exc)
        sys.exit(1)

    data: list[dict[str, Any]] = resp.json()
    if not isinstance(data, list):
        logger.error("API 返回格式异常（期望 list，收到 %s）", type(data).__name__)
        sys.exit(1)

    logger.info("成功获取 %d 首歌曲数据", len(data))
    return data


# ---------------------------------------------------------------------------
# 数据清洗
# ---------------------------------------------------------------------------

def clean_record(raw: dict[str, Any]) -> dict[str, Any]:
    """将单条 API 记录清洗为数据库行字典。"""
    ds = raw.get("ds", [])
    levels = raw.get("level", [])

    row: dict[str, Any] = {
        "id": int(raw["id"]),
        "title": raw.get("title", ""),
        "type": raw.get("type", "SD"),
    }

    for i, label in enumerate(DIFF_LABELS):
        row[f"ds_{label}"] = ds[i] if i < len(ds) else None
        row[f"level_{label}"] = str(levels[i]) if i < len(levels) else None

    return row


# ---------------------------------------------------------------------------
# 数据库操作
# ---------------------------------------------------------------------------

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS music_data (
    id              INTEGER PRIMARY KEY,
    title           TEXT    NOT NULL,
    type            TEXT    NOT NULL,
    ds_basic        REAL,
    ds_advanced     REAL,
    ds_expert       REAL,
    ds_master       REAL,
    ds_remaster     REAL,
    level_basic     TEXT,
    level_advanced  TEXT,
    level_expert    TEXT,
    level_master    TEXT,
    level_remaster  TEXT
);
"""

INSERT_SQL = """
INSERT OR REPLACE INTO music_data (
    id, title, type,
    ds_basic, ds_advanced, ds_expert, ds_master, ds_remaster,
    level_basic, level_advanced, level_expert, level_master, level_remaster
) VALUES (
    :id, :title, :type,
    :ds_basic, :ds_advanced, :ds_expert, :ds_master, :ds_remaster,
    :level_basic, :level_advanced, :level_expert, :level_master, :level_remaster
);
"""


def sync_to_database(records: list[dict[str, Any]]) -> None:
    """原子刷新曲库表，同时保留成绩、导入批次和其他用户数据。"""
    songs, charts = parse_diving_fish_music_data(records)
    conn = database.connect(default_db_path())
    try:
        database.replace_catalog(conn, songs, charts)
        integrity = conn.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError(f"数据库完整性检查失败: {integrity}")
    finally:
        conn.close()
    logger.info("数据库曲库已更新: %s (%d 首歌曲，%d 张谱面)", default_db_path(), len(songs), len(charts))


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

def main() -> None:
    raw_data = fetch_music_data()
    sync_to_database(raw_data)
    logger.info("同步完成。")


if __name__ == "__main__":
    main()
