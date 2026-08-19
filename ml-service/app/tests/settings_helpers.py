from collections.abc import Iterator
from contextlib import contextmanager
from typing import Any

from app.config.settings import Settings, settings


class SettingsOverride:
    def __init__(self, target: Settings = settings) -> None:
        self.target = target
        self._originals: dict[str, Any] = {}

    def set(self, **values: Any) -> None:
        for key, value in values.items():
            if key not in self._originals:
                self._originals[key] = getattr(self.target, key)
            setattr(self.target, key, value)

    def restore(self) -> None:
        for key, value in reversed(list(self._originals.items())):
            setattr(self.target, key, value)
        self._originals.clear()


@contextmanager
def override_settings(**values: Any) -> Iterator[None]:
    override = SettingsOverride()
    override.set(**values)
    try:
        yield
    finally:
        override.restore()
