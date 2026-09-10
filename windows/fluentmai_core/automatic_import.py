from __future__ import annotations

from dataclasses import dataclass
import threading
import time
from typing import Callable

from .capture_session import LocalCaptureController
from .import_pipeline import import_parsed_records
from .models import ImportSummary
from .wahlap import parse_wahlap_pages


ImportProgress = Callable[[str, dict], None]


@dataclass(frozen=True)
class CaptureImportResult:
    summary: ImportSummary
    captured_pages: int
    captured_bytes: int
    helper_version: str
    certificate_fingerprint: str
    elapsed_seconds: float


def run_wahlap_capture_import(
    controller: LocalCaptureController,
    *,
    db_path: str | None = None,
    progress: ImportProgress | None = None,
    cancel_event: threading.Event | None = None,
    wait_timeout: float = 180,
    request_timeout: float = 30,
    retries: int = 2,
    retry_delay: float = 1.5,
    install_ca: bool = True,
) -> CaptureImportResult:
    progress = progress or (lambda _stage, _info: None)
    started = time.perf_counter()
    capture = controller.capture(
        progress=progress,
        cancel_event=cancel_event,
        wait_timeout=wait_timeout,
        request_timeout=request_timeout,
        retries=retries,
        retry_delay=retry_delay,
        install_ca=install_ca,
    )
    captured_pages = capture.captured_pages
    captured_bytes = capture.captured_bytes
    helper_version = capture.helper_version
    certificate_fingerprint = capture.certificate_fingerprint
    pages = capture.pages
    capture.home_html = ""
    try:
        progress("parsing", {"captured_pages": captured_pages})
        records = parse_wahlap_pages(pages, db_path=db_path)
    finally:
        pages.clear()
        capture.pages.clear()
    progress("validating_and_writing", {"parsed": len(records)})
    try:
        summary = import_parsed_records(
            records,
            source="wahlap-wechat",
            db_path=db_path,
            fetched_count=len(records),
            message="Local WeChat capture",
        )
    finally:
        records.clear()
    progress(
        "local_import_complete",
        {
            "parsed": summary.parsed_count,
            "inserted": summary.inserted,
            "updated": summary.updated,
            "duplicate": summary.skipped_duplicate,
            "quarantined": summary.quarantined,
            "b35": summary.b35_rating,
            "b15": summary.b15_rating,
            "rating": summary.rating_after,
        },
    )
    return CaptureImportResult(
        summary=summary,
        captured_pages=captured_pages,
        captured_bytes=captured_bytes,
        helper_version=helper_version,
        certificate_fingerprint=certificate_fingerprint,
        elapsed_seconds=time.perf_counter() - started,
    )
