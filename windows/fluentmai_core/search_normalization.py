from __future__ import annotations

import os
import re
import unicodedata


_SEPARATORS = re.compile(r"[\s._·・:：!！?？'\"“”‘’()（）\[\]【】/\\\-]+")


def to_simplified_chinese(value: str) -> str:
    """Use Windows' maintained locale mapping without bundling a dictionary copy."""

    if not value or os.name != "nt":
        return value.translate(str.maketrans("體譜臺灣樂與", "体谱台湾乐与"))
    try:
        import ctypes

        flag = 0x02000000  # LCMAP_SIMPLIFIED_CHINESE
        function = ctypes.windll.kernel32.LCMapStringEx
        required = function("zh-CN", flag, value, -1, None, 0, None, None, 0)
        if required <= 0:
            return value
        target = ctypes.create_unicode_buffer(required)
        written = function("zh-CN", flag, value, -1, target, required, None, None, 0)
        return target.value if written > 0 else value
    except (AttributeError, OSError, ValueError):
        return value


def normalize_search(value: str | None) -> str:
    compatible = unicodedata.normalize("NFKC", (value or "").strip()).casefold()
    return _SEPARATORS.sub("", to_simplified_chinese(compatible))
