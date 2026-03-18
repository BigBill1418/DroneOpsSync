import hashlib
import os
from pathlib import Path

import aiofiles
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse

app = FastAPI(title="DroneOpsSync Server", version="1.0.0")

UPLOAD_DIR = Path(os.getenv("UPLOAD_DIR", "/data/flightlogs"))
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)


@app.get("/health")
async def health():
    return {"status": "ok", "upload_dir": str(UPLOAD_DIR)}


@app.post("/upload")
async def upload_file(file: UploadFile = File(...)):
    if not file.filename:
        raise HTTPException(status_code=400, detail="No filename provided")

    # Sanitize filename — strip any path components
    safe_name = Path(file.filename).name
    if not safe_name:
        raise HTTPException(status_code=400, detail="Invalid filename")

    dest = UPLOAD_DIR / safe_name
    sha256 = hashlib.sha256()

    try:
        async with aiofiles.open(dest, "wb") as out:
            while chunk := await file.read(8192):
                sha256.update(chunk)
                await out.write(chunk)
    except Exception as e:
        # Clean up partial file on failure
        if dest.exists():
            dest.unlink()
        raise HTTPException(status_code=500, detail=f"Write failed: {e}")

    return JSONResponse({
        "filename": safe_name,
        "checksum": sha256.hexdigest(),
        "size": dest.stat().st_size,
    })
