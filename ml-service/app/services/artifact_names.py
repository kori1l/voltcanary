from pathlib import PurePosixPath


def relative_artifact_path(value: str) -> str:
    """Accept only canonical relative artifact names on every host platform."""
    if not isinstance(value, str) or not value or "\\" in value or ":" in value or "\x00" in value:
        raise ValueError("invalid relative artifact path")
    path = PurePosixPath(value)
    if path.is_absolute() or any(part in ("", ".", "..") for part in value.split("/")):
        raise ValueError("invalid relative artifact path")
    return path.as_posix()
