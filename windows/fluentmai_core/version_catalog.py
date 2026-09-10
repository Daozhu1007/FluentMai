from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class PlateVersion:
    prefixes: tuple[str, ...]
    chart_version_start: int
    chart_version_end: int
    excluded_song_ids: frozenset[int] = frozenset()
    supported_kinds: frozenset[str] = frozenset({"general", "extreme", "god", "maimai"})

    def contains(self, song_id: int, chart_version: int) -> bool:
        return (
            self.chart_version_start <= chart_version < self.chart_version_end
            and song_id not in self.excluded_song_ids
        )


@dataclass(frozen=True)
class VersionReference:
    version_id: int
    official_name: str
    generation: str
    related_names: tuple[str, ...] = ()
    plate: PlateVersion | None = None


_TRUE_KINDS = frozenset({"extreme", "god", "maimai"})


KNOWN_VERSIONS: tuple[VersionReference, ...] = (
    VersionReference(10000, "maimai", "经典世代", ("maimai PLUS まで",), PlateVersion(("真",), 10000, 12000, frozenset({44, 70, 146}), _TRUE_KINDS)),
    VersionReference(11000, "maimai PLUS", "经典世代"),
    VersionReference(12000, "GreeN", "经典世代", plate=PlateVersion(("超",), 12000, 13000, frozenset({185, 189, 190}))),
    VersionReference(13000, "GreeN PLUS", "经典世代", plate=PlateVersion(("檄",), 13000, 14000, frozenset({341}))),
    VersionReference(14000, "ORANGE", "经典世代", plate=PlateVersion(("橙",), 14000, 15000, frozenset({281}))),
    VersionReference(15000, "ORANGE PLUS", "经典世代", plate=PlateVersion(("暁",), 15000, 16000, frozenset({419}))),
    VersionReference(16000, "PiNK", "经典世代", plate=PlateVersion(("桃",), 16000, 17000, frozenset({451, 455, 460}))),
    VersionReference(17000, "PiNK PLUS", "经典世代", plate=PlateVersion(("櫻",), 17000, 18000, frozenset({524}))),
    VersionReference(18000, "MURASAKi", "经典世代", plate=PlateVersion(("紫",), 18000, 18500)),
    VersionReference(18500, "MURASAKi PLUS", "经典世代", plate=PlateVersion(("菫",), 18500, 19000, frozenset({853}))),
    VersionReference(19000, "MiLK", "经典世代", plate=PlateVersion(("白",), 19000, 19500, frozenset({687, 688, 712}))),
    VersionReference(19500, "MiLK PLUS", "经典世代", plate=PlateVersion(("雪",), 19500, 19900, frozenset({731}))),
    VersionReference(19900, "FiNALE", "经典世代", plate=PlateVersion(("輝",), 19900, 20000, frozenset({792}))),
    VersionReference(20000, "舞萌DX", "DX 世代", ("maimai でらっくす / PLUS",), PlateVersion(("熊", "華"), 20000, 21000, frozenset({146}))),
    VersionReference(21000, "舞萌DX 2021", "DX 世代", ("Splash / Splash PLUS",), PlateVersion(("爽", "煌"), 21000, 22000, frozenset({1213}))),
    VersionReference(22000, "舞萌DX 2022", "DX 世代", ("UNiVERSE / UNiVERSE PLUS",), PlateVersion(("星", "宙"), 22000, 23000, frozenset({1253, 1267}))),
    VersionReference(23000, "舞萌DX 2023", "DX 世代", ("FESTiVAL / FESTiVAL PLUS",), PlateVersion(("祭", "祝"), 23000, 24000)),
    VersionReference(24000, "舞萌DX 2024", "DX 世代", ("BUDDiES / BUDDiES PLUS",), PlateVersion(("双", "宴"), 24000, 25000)),
    VersionReference(25000, "舞萌DX 2025", "DX 世代", ("PRiSM / PRiSM PLUS",), PlateVersion(("鏡",), 25000, 25500)),
    VersionReference(25500, "舞萌DX 2026", "DX 世代", ("当前曲库批次",)),
)


def version_reference_for(version_id: int | None) -> VersionReference | None:
    if version_id is None:
        return None
    matches = [item for item in KNOWN_VERSIONS if item.version_id <= int(version_id)]
    if not matches:
        return None
    result = matches[-1]
    if result == KNOWN_VERSIONS[-1] and int(version_id) >= result.version_id + 500:
        return None
    return result


def plate_version_for(version_id: int | None) -> PlateVersion | None:
    return next((item.plate for item in KNOWN_VERSIONS if item.version_id == version_id), None)


def version_name_for(version_id: int | None) -> str:
    reference = version_reference_for(version_id)
    return reference.official_name if reference else ""
