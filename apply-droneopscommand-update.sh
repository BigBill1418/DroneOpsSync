#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# DroneOpsSync integration — applies device API key backend to DroneOpsCommand
# Run this once from any machine where you have GitHub access:
#   bash apply-droneopscommand-update.sh
# ─────────────────────────────────────────────────────────────────────────────
set -e

REPO_URL="https://github.com/BigBill1418/DroneOpsCommand.git"
BRANCH="claude/device-upload-pN2gE"
WORK_DIR="/tmp/DroneOpsCommand-update-$$"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  DroneOpsSync Integration — DroneOpsCommand Backend     ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""
echo "→ Cloning DroneOpsCommand into $WORK_DIR ..."
git clone "$REPO_URL" "$WORK_DIR"
cd "$WORK_DIR"

echo "→ Creating branch $BRANCH ..."
git checkout -b "$BRANCH"

# ── 1. New file: backend/app/models/device_api_key.py ────────────────────────
echo "→ Writing backend/app/models/device_api_key.py ..."
cat > backend/app/models/device_api_key.py << 'PYEOF'
"""Device API key model — allows field controllers to authenticate without a user login."""

import uuid
from datetime import datetime

from sqlalchemy import String, DateTime, Boolean
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base


class DeviceApiKey(Base):
    __tablename__ = "device_api_keys"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    label: Mapped[str] = mapped_column(String(255), nullable=False)
    # SHA-256 hex digest of the raw key — the raw key is never stored
    key_hash: Mapped[str] = mapped_column(String(64), unique=True, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
PYEOF

# ── 2. New file: backend/app/auth/device.py ──────────────────────────────────
echo "→ Writing backend/app/auth/device.py ..."
cat > backend/app/auth/device.py << 'PYEOF'
"""Device API key authentication dependency for field controllers."""

import hashlib
from datetime import datetime

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.device_api_key import DeviceApiKey


async def validate_device_api_key(
    x_device_api_key: str = Header(..., description="Device API key from DroneOpsCommand Settings → Device Access"),
    db: AsyncSession = Depends(get_db),
) -> DeviceApiKey:
    """Dependency that validates X-Device-Api-Key header against stored key hashes.

    The raw key is never stored — only its SHA-256 digest.  A 401 is returned for
    any key that is missing, unknown, or has been revoked.
    """
    key_hash = hashlib.sha256(x_device_api_key.encode()).hexdigest()

    result = await db.execute(
        select(DeviceApiKey).where(
            DeviceApiKey.key_hash == key_hash,
            DeviceApiKey.is_active.is_(True),
        )
    )
    device_key = result.scalar_one_or_none()

    if not device_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or revoked device API key",
        )

    # Record last-used timestamp (best effort — do not fail the request if this write fails)
    try:
        device_key.last_used_at = datetime.utcnow()
    except Exception:
        pass

    return device_key
PYEOF

# ── 3. New file: backend/app/routers/device_keys.py ──────────────────────────
echo "→ Writing backend/app/routers/device_keys.py ..."
cat > backend/app/routers/device_keys.py << 'PYEOF'
"""Device API key management — Settings → Device Access.

Allows admins to create, list, and revoke static API keys used by field
controllers (DroneOpsSync) to upload flight logs without a user login.
"""

import hashlib
import secrets
import uuid
from datetime import datetime

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth.jwt import get_current_user
from app.database import get_db
from app.models.device_api_key import DeviceApiKey
from app.models.user import User

router = APIRouter(prefix="/api/settings/device-keys", tags=["settings"])


class DeviceKeyResponse(BaseModel):
    id: uuid.UUID
    label: str
    is_active: bool
    created_at: datetime
    last_used_at: datetime | None

    model_config = {"from_attributes": True}


class DeviceKeyCreateResponse(DeviceKeyResponse):
    """Returned ONCE at creation — includes the raw key which is never stored."""
    raw_key: str


class DeviceKeyCreate(BaseModel):
    label: str


@router.get("", response_model=list[DeviceKeyResponse])
async def list_device_keys(
    _user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """List all device API keys (raw keys are never returned after creation)."""
    result = await db.execute(
        select(DeviceApiKey).order_by(DeviceApiKey.created_at.desc())
    )
    return result.scalars().all()


@router.post("", response_model=DeviceKeyCreateResponse, status_code=201)
async def create_device_key(
    payload: DeviceKeyCreate,
    _user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Create a new device API key.

    The raw key is returned exactly ONCE in this response and is never stored.
    Copy it to your DroneOpsSync controller immediately.
    """
    raw_key = secrets.token_urlsafe(32)
    key_hash = hashlib.sha256(raw_key.encode()).hexdigest()

    device_key = DeviceApiKey(
        label=payload.label.strip() or "Unnamed Device",
        key_hash=key_hash,
    )
    db.add(device_key)
    await db.flush()
    await db.refresh(device_key)
    await db.commit()

    return DeviceKeyCreateResponse(
        id=device_key.id,
        label=device_key.label,
        is_active=device_key.is_active,
        created_at=device_key.created_at,
        last_used_at=device_key.last_used_at,
        raw_key=raw_key,
    )


@router.delete("/{key_id}", status_code=204)
async def revoke_device_key(
    key_id: uuid.UUID,
    _user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Revoke (permanently delete) a device API key.

    Any controller using this key will immediately lose upload access.
    """
    result = await db.execute(
        select(DeviceApiKey).where(DeviceApiKey.id == key_id)
    )
    device_key = result.scalar_one_or_none()
    if not device_key:
        raise HTTPException(status_code=404, detail="Device key not found")

    await db.delete(device_key)
    await db.commit()
PYEOF

# ── 4. Patch backend/app/models/__init__.py ───────────────────────────────────
echo "→ Patching backend/app/models/__init__.py ..."
# Add the import and __all__ entry
python3 - << 'PYEOF'
import re

path = "backend/app/models/__init__.py"
with open(path) as f:
    content = f.read()

# Add import line after last existing import
if "device_api_key" not in content:
    content = content.rstrip() + "\nfrom app.models.device_api_key import DeviceApiKey\n"

# Add to __all__
content = content.replace(
    '"MaintenanceSchedule",\n]',
    '"MaintenanceSchedule",\n    "DeviceApiKey",\n]'
)

with open(path, "w") as f:
    f.write(content)
print("  __init__.py updated")
PYEOF

# ── 5. Patch backend/app/routers/flight_library.py ───────────────────────────
echo "→ Patching backend/app/routers/flight_library.py ..."
python3 - << 'PYEOF'
path = "backend/app/routers/flight_library.py"
with open(path) as f:
    content = f.read()

if "device-upload" in content:
    print("  flight_library.py already patched — skipping")
else:
    # Add imports after "from app.auth.jwt import get_current_user"
    content = content.replace(
        "from app.auth.jwt import get_current_user",
        "from app.auth.device import validate_device_api_key\nfrom app.auth.jwt import get_current_user"
    )
    content = content.replace(
        "from app.models.flight import Flight",
        "from app.models.device_api_key import DeviceApiKey\nfrom app.models.flight import Flight"
    )

    # Insert the device-upload endpoint before the manual flight entry section
    device_upload = '''
# ── Device upload (field controllers via X-Device-Api-Key) ───────────
@router.post("/device-upload", response_model=FlightUploadResponse)
async def device_upload_flights(
    files: list[UploadFile] = File(...),
    db: AsyncSession = Depends(get_db),
    _device: DeviceApiKey = Depends(validate_device_api_key),
):
    """Upload flight logs from a field controller using a static device API key.

    Identical processing to /upload but authenticates via X-Device-Api-Key header
    instead of a user JWT, allowing automated sync from DroneOpsSync without
    requiring a human login session on the controller.
    """
    imported = []
    skipped = 0
    errors = []

    for upload in files:
        try:
            content = await upload.read()
            file_hash = hashlib.sha256(content).hexdigest()

            existing = await db.execute(
                select(Flight).where(Flight.source_file_hash == file_hash)
            )
            if existing.scalar_one_or_none():
                skipped += 1
                continue

            async with httpx.AsyncClient(timeout=120) as client:
                resp = await client.post(
                    f"{PARSER_URL}/parse",
                    files={"file": (upload.filename or "upload.txt", content)},
                )
                if resp.status_code != 200:
                    errors.append(f"{upload.filename}: parser returned {resp.status_code}")
                    continue

                data = resp.json()
                if data.get("errors"):
                    errors.extend(data["errors"])

                for parsed in data.get("flights", []):
                    ph = parsed.get("file_hash", file_hash)
                    dup = await db.execute(select(Flight).where(Flight.source_file_hash == ph))
                    if dup.scalar_one_or_none():
                        skipped += 1
                        continue

                    flight = Flight(
                        name=parsed.get("name", upload.filename or "Unknown"),
                        drone_model=parsed.get("drone_model"),
                        drone_serial=parsed.get("drone_serial"),
                        battery_serial=parsed.get("battery_serial"),
                        start_time=parsed.get("start_time"),
                        duration_secs=parsed.get("duration_secs", 0),
                        total_distance=parsed.get("total_distance", 0),
                        max_altitude=parsed.get("max_altitude", 0),
                        max_speed=parsed.get("max_speed", 0),
                        home_lat=parsed.get("home_lat"),
                        home_lon=parsed.get("home_lon"),
                        point_count=parsed.get("point_count", 0),
                        gps_track=parsed.get("gps_track"),
                        telemetry=parsed.get("telemetry"),
                        raw_metadata=parsed.get("raw_metadata"),
                        source=parsed.get("source", "dji_txt"),
                        source_file_hash=ph,
                        original_filename=parsed.get("original_filename", upload.filename),
                    )
                    db.add(flight)
                    await db.flush()
                    await db.refresh(flight)
                    imported.append(flight)

                    battery_data = parsed.get("battery_data")
                    if battery_data and battery_data.get("serial"):
                        await _track_battery(db, flight, battery_data)

        except httpx.ConnectError:
            errors.append(f"{upload.filename}: flight-parser service unavailable")
        except Exception as e:
            errors.append(f"{upload.filename}: {str(e)}")

    return FlightUploadResponse(
        imported=len(imported),
        skipped=skipped,
        errors=errors,
        flights=imported,
    )

'''
    content = content.replace(
        "# ── Manual flight entry",
        device_upload + "# ── Manual flight entry"
    )

    with open(path, "w") as f:
        f.write(content)
    print("  flight_library.py updated")
PYEOF

# ── 6. Patch backend/app/main.py ─────────────────────────────────────────────
echo "→ Patching backend/app/main.py ..."
python3 - << 'PYEOF'
path = "backend/app/main.py"
with open(path) as f:
    content = f.read()

if "device_keys" not in content:
    content = content.replace(
        "from app.routers import auth,",
        "from app.routers import auth,"
    )
    # Add device_keys to the router imports
    content = content.replace(
        "flight_library, batteries, maintenance",
        "flight_library, batteries, maintenance, device_keys"
    )
    # Register the router
    content = content.replace(
        "app.include_router(maintenance.router)",
        "app.include_router(maintenance.router)\napp.include_router(device_keys.router)"
    )
    with open(path, "w") as f:
        f.write(content)
    print("  main.py updated")
else:
    print("  main.py already patched — skipping")

# Starlette 0.31+ defaults MultiPartParser.max_file_size to 1 MB which is too
# small for DJI flight logs (typically 3–8 MB).  Raise it to 50 MB globally.
with open(path) as f:
    content = f.read()

file_size_patch = "from starlette.formparsers import MultiPartParser\nMultiPartParser.max_file_size = 50 * 1024 * 1024  # 50 MB — DJI logs can be 3–8 MB\n\n"
if "MultiPartParser.max_file_size" not in content:
    # Insert after the last top-level import block, before app = FastAPI(...)
    content = content.replace("app = FastAPI(", file_size_patch + "app = FastAPI(", 1)
    with open(path, "w") as f:
        f.write(content)
    print("  main.py: MultiPartParser.max_file_size raised to 50 MB")
else:
    print("  main.py: max_file_size already configured — skipping")
PYEOF

# ── Commit and push ───────────────────────────────────────────────────────────
echo ""
echo "→ Committing changes ..."
git add \
  backend/app/models/device_api_key.py \
  backend/app/auth/device.py \
  backend/app/routers/device_keys.py \
  backend/app/routers/flight_library.py \
  backend/app/models/__init__.py \
  backend/app/main.py

git commit -m "feat: device API key auth and /device-upload endpoint for DroneOpsSync

- DeviceApiKey model (device_api_keys table, auto-created on startup)
- validate_device_api_key() dependency (X-Device-Api-Key header, SHA-256)
- POST /api/flight-library/device-upload (device key auth, same parse logic)
- GET/POST/DELETE /api/settings/device-keys (admin key management)

Field controllers running DroneOpsSync can now push flight logs through
the Cloudflare tunnel using a static API key — no user login required."

echo "→ Pushing branch $BRANCH ..."
git push -u origin "$BRANCH"

echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  Done! Branch pushed: $BRANCH"
echo "║                                                          ║"
echo "║  Next: open a PR on GitHub and merge into main.         ║"
echo "║  The device_api_keys table is created automatically     ║"
echo "║  when the DroneOpsCommand container next restarts.      ║"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

# Cleanup
cd /
rm -rf "$WORK_DIR"
