"""ScanForge package (.sf3d) reading and verification (pure Python, no bpy).

Format: docs/spec/package-format.md. A ZIP archive with package.json listing every file with its
size and SHA-256. Every file is verified before anything is extracted or imported.
"""

import hashlib
import json
import os
import posixpath
import zipfile

PACKAGE_FORMAT_ID = "scanforge.package"
SUPPORTED_PACKAGE_VERSION = 1
SUPPORTED_PROJECT_SCHEMA = 1
MAX_UNCOMPRESSED_BYTES = 8 * 1024 ** 3


class PackageError(Exception):
    """Raised when a package is malformed, tampered with or unsupported."""


def sha256_file(path, chunk=1 << 20):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def check_safe_path(path):
    """Rejects absolute paths, drive letters, backslashes and parent traversal (zip slip)."""
    if not path or path.startswith(("/", "\\")) or "\\" in path or ":" in path:
        raise PackageError("Unsafe path in package: %r" % path)
    norm = posixpath.normpath(path)
    if norm.startswith("..") or "/../" in "/" + norm + "/" or norm != path:
        raise PackageError("Unsafe path in package: %r" % path)


class Package:
    def __init__(self, path, manifest, project):
        self.path = path
        self.manifest = manifest
        self.project = project

    @property
    def name(self):
        return self.manifest.get("project_name") or self.project.get("name") or "Scan"

    @property
    def primary_model(self):
        return self.manifest["primary_model"]

    def summary(self):
        """Human-readable information shown before import."""
        current = None
        model = self.project.get("model", {})
        versions = model.get("versions", [])
        cur = model.get("current_version", 0)
        if 0 < cur <= len(versions):
            current = versions[cur - 1]
        scale = self.project.get("scale", {})
        return {
            "name": self.name,
            "project_id": self.manifest.get("project_id"),
            "created_at": self.project.get("created_at"),
            "files": len(self.manifest.get("files", [])),
            "bytes": sum(f["bytes"] for f in self.manifest.get("files", [])),
            "vertices": current and current.get("vertex_count"),
            "triangles": current and current.get("triangle_count"),
            "watertight": current and current.get("watertight"),
            "holes": current and current.get("hole_count"),
            "has_colors": current and current.get("has_vertex_colors"),
            "scale_source": scale.get("source"),
            "scale_factor": scale.get("factor", 1.0),
            "capture_source": (self.project.get("capture") or {}).get("source"),
            "warnings": (self.project.get("reconstruction") or {}).get("warnings", []),
        }


def verify(path):
    """Fully verifies a package and returns a Package. Raises PackageError."""
    if not zipfile.is_zipfile(path):
        raise PackageError("Not a ZIP-based ScanForge package")
    with zipfile.ZipFile(path) as z:
        try:
            manifest = json.loads(z.read("package.json").decode("utf-8"))
        except KeyError:
            raise PackageError("package.json missing")
        except ValueError as e:
            raise PackageError("package.json unreadable: %s" % e)
        if manifest.get("format") != PACKAGE_FORMAT_ID:
            raise PackageError("Not a ScanForge package")
        if int(manifest.get("format_version", 0)) > SUPPORTED_PACKAGE_VERSION:
            raise PackageError("Package format %s is newer than this add-on supports; update the add-on"
                               % manifest.get("format_version"))
        files = manifest.get("files", [])
        if sum(int(f["bytes"]) for f in files) > MAX_UNCOMPRESSED_BYTES:
            raise PackageError("Package is unreasonably large")
        names = set(z.namelist())
        for entry in files:
            p = entry["path"]
            check_safe_path(p)
            if p not in names:
                raise PackageError("Missing file %s" % p)
            h = hashlib.sha256()
            size = 0
            with z.open(p) as f:
                while True:
                    b = f.read(1 << 20)
                    if not b:
                        break
                    size += len(b)
                    h.update(b)
            if size != int(entry["bytes"]):
                raise PackageError("Size mismatch for %s" % p)
            if h.hexdigest() != entry["sha256"]:
                raise PackageError("Checksum mismatch for %s" % p)
        if manifest.get("primary_model") not in {f["path"] for f in files}:
            raise PackageError("Primary model is not listed in the manifest")
        try:
            project = json.loads(z.read("project.json").decode("utf-8"))
        except (KeyError, ValueError):
            raise PackageError("project.json missing or unreadable")
        if int(project.get("schema_version", 0)) > SUPPORTED_PROJECT_SCHEMA:
            raise PackageError("Project schema %s is newer than this add-on supports" % project.get("schema_version"))
    return Package(path, manifest, project)


def extract(package, dest_dir):
    """Extracts verified files into dest_dir (created). Returns the absolute primary model path."""
    os.makedirs(dest_dir, exist_ok=True)
    root = os.path.realpath(dest_dir)
    with zipfile.ZipFile(package.path) as z:
        for entry in package.manifest["files"]:
            p = entry["path"]
            check_safe_path(p)
            target = os.path.realpath(os.path.join(root, *p.split("/")))
            if not target.startswith(root + os.sep):
                raise PackageError("Unsafe path in package: %r" % p)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with z.open(p) as src, open(target, "wb") as dst:
                while True:
                    b = src.read(1 << 20)
                    if not b:
                        break
                    dst.write(b)
    return os.path.join(root, *package.primary_model.split("/"))
