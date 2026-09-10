from __future__ import annotations

from collections.abc import Iterable

from PyQt6.QtCore import QRect, QSize


DEFAULT_WINDOW_SIZE = QSize(1180, 760)
BASE_MINIMUM_SIZE = QSize(760, 520)
WINDOW_MARGIN = 16


def usable_window_rect(available: QRect, margin: int = WINDOW_MARGIN) -> QRect:
    """Inset a screen work area without producing an invalid rectangle."""

    horizontal = min(margin, max(0, (available.width() - 1) // 2))
    vertical = min(margin, max(0, (available.height() - 1) // 2))
    return available.adjusted(horizontal, vertical, -horizontal, -vertical)


def adaptive_minimum_size(available: QRect) -> QSize:
    """Keep the shell useful without allowing its minimum to exceed the screen."""

    usable = usable_window_rect(available)
    return QSize(
        max(1, min(BASE_MINIMUM_SIZE.width(), usable.width())),
        max(1, min(BASE_MINIMUM_SIZE.height(), usable.height())),
    )


def centered_window_rect(
    available: QRect,
    desired: QSize = DEFAULT_WINDOW_SIZE,
) -> QRect:
    usable = usable_window_rect(available)
    width = max(1, min(desired.width(), usable.width()))
    height = max(1, min(desired.height(), usable.height()))
    left = usable.left() + (usable.width() - width) // 2
    top = usable.top() + (usable.height() - height) // 2
    return QRect(left, top, width, height)


def clamp_window_rect(rect: QRect, available_screens: Iterable[QRect]) -> QRect:
    """Fit a restored window fully inside the screen it overlaps most.

    If the previous monitor is gone, the first work area is used as a safe
    fallback.  The caller should pass the primary screen first.
    """

    screens = [screen for screen in available_screens if screen.isValid()]
    if not screens:
        return QRect(rect)

    def intersection_area(screen: QRect) -> int:
        intersection = rect.intersected(screen)
        return max(0, intersection.width()) * max(0, intersection.height())

    target = max(screens, key=intersection_area)
    if intersection_area(target) == 0:
        target = screens[0]
    usable = usable_window_rect(target)

    width = max(1, min(rect.width(), usable.width()))
    height = max(1, min(rect.height(), usable.height()))
    left = min(max(rect.left(), usable.left()), usable.right() - width + 1)
    top = min(max(rect.top(), usable.top()), usable.bottom() - height + 1)
    return QRect(left, top, width, height)
