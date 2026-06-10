import base64
import hashlib
import hmac
import json
import mimetypes
import os
import posixpath
import re
import time
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import quote, urlparse
from urllib.request import Request, urlopen
from urllib.error import HTTPError


LOCAL_STORAGE_DRIVER = "local"
R2_STORAGE_DRIVER = "r2"
SUPPORTED_STORAGE_DRIVERS = {LOCAL_STORAGE_DRIVER, R2_STORAGE_DRIVER}


class R2UploadError(RuntimeError):
    pass


def _is_truthy(value):
    return str(value or "").strip().lower() in {"1", "true", "yes", "on"}


def get_public_image_storage_driver(environ=os.environ):
    driver = (environ.get("NXR_STORAGE_DRIVER") or LOCAL_STORAGE_DRIVER).strip().lower()
    if not driver:
        return LOCAL_STORAGE_DRIVER
    if driver not in SUPPORTED_STORAGE_DRIVERS:
        raise ValueError(
            f"Unsupported NXR_STORAGE_DRIVER '{driver}'. "
            f"Expected one of: {', '.join(sorted(SUPPORTED_STORAGE_DRIVERS))}"
        )
    return driver


def is_remote_image_url(value):
    return (value or "").strip().lower().startswith(("http://", "https://"))


def _env_value(name, environ):
    value = (environ.get(name) or "").strip()
    if not value:
        raise ValueError(f"{name} is required when NXR_STORAGE_DRIVER=r2")
    return value


def _safe_key_part(value, fallback):
    raw_value = (value or "").strip()
    cleaned = re.sub(r"[^A-Za-z0-9._-]+", "-", raw_value).strip(".-_")
    return cleaned or fallback


def build_r2_object_key(cert_id, side, source_path, environ=os.environ):
    source_path = Path(source_path)
    prefix = (environ.get("R2_OBJECT_PREFIX") or "cards").strip().strip("/")
    cert_part = _safe_key_part(cert_id, "card")
    side_part = _safe_key_part(side, "image")
    source_part = _safe_key_part(source_path.stem, "upload")
    extension = source_path.suffix.lower() or ".jpg"
    object_name = f"{cert_part}_{side_part}_{source_part}{extension}"
    return posixpath.join(prefix, cert_part, object_name) if prefix else posixpath.join(cert_part, object_name)


def build_r2_public_url(object_key, environ=os.environ):
    public_base_url = _env_value("R2_PUBLIC_BASE_URL", environ).rstrip("/")
    return f"{public_base_url}/{quote(object_key, safe='/')}"


def _base64url_encode(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def _build_jwt(header, payload, secret):
    encoded_header = _base64url_encode(json.dumps(header, separators=(",", ":")).encode("utf-8"))
    encoded_payload = _base64url_encode(json.dumps(payload, separators=(",", ":")).encode("utf-8"))
    signing_input = f"{encoded_header}.{encoded_payload}".encode("utf-8")
    signature = hmac.new(secret.encode("utf-8"), signing_input, hashlib.sha256).digest()
    return f"{encoded_header}.{encoded_payload}.{_base64url_encode(signature)}"


def create_r2_upload_only_credentials(object_key, environ=os.environ):
    account_id = _env_value("R2_ACCOUNT_ID", environ)
    access_key_id = _env_value("R2_ACCESS_KEY_ID", environ)
    secret_access_key = _env_value("R2_SECRET_ACCESS_KEY", environ)
    bucket = _env_value("R2_BUCKET", environ)
    endpoint_host = urlparse(_env_value("R2_ENDPOINT", environ)).netloc
    now = int(time.time())
    ttl_seconds = int((environ.get("R2_TEMP_CREDENTIAL_TTL_SECONDS") or "900").strip() or "900")
    if ttl_seconds <= 0:
        raise ValueError("R2_TEMP_CREDENTIAL_TTL_SECONDS must be greater than 0")

    jwt = _build_jwt(
        {"alg": "HS256", "typ": "JWT"},
        {
            "iss": access_key_id,
            "sub": account_id,
            "aud": endpoint_host,
            "iat": now,
            "exp": now + ttl_seconds,
            "bucket": bucket,
            "actions": ["PutObject"],
            "paths": {
                "objectPaths": [object_key],
            },
        },
        secret_access_key,
    )

    return {
        "access_key_id": access_key_id,
        "secret_access_key": hashlib.sha256(jwt.encode("utf-8")).hexdigest(),
        "session_token": base64.b64encode(f"jwt/{jwt}".encode("utf-8")).decode("ascii"),
        "expires_at": now + ttl_seconds,
        "actions": ["PutObject"],
        "object_key": object_key,
    }


def _signing_key(secret_key, date_stamp, region, service):
    key = ("AWS4" + secret_key).encode("utf-8")
    for value in (date_stamp, region, service, "aws4_request"):
        key = hmac.new(key, value.encode("utf-8"), hashlib.sha256).digest()
    return key


def _build_signed_request(method, url, body=b"", headers=None, environ=os.environ, credentials=None):
    parsed_url = urlparse(url)
    host = parsed_url.netloc
    credentials = credentials or {}
    access_key_id = credentials.get("access_key_id") or _env_value("R2_ACCESS_KEY_ID", environ)
    secret_access_key = credentials.get("secret_access_key") or _env_value("R2_SECRET_ACCESS_KEY", environ)
    session_token = credentials.get("session_token") or ""
    region = (environ.get("R2_REGION") or "auto").strip() or "auto"
    service = "s3"
    now = datetime.now(timezone.utc)
    amz_date = now.strftime("%Y%m%dT%H%M%SZ")
    date_stamp = now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()

    headers = {
        **(headers or {}),
        "host": host,
        "x-amz-content-sha256": payload_hash,
        "x-amz-date": amz_date,
    }
    if session_token:
        headers["x-amz-security-token"] = session_token
    signed_headers = ";".join(sorted(headers))
    canonical_headers = "".join(f"{key}:{headers[key]}\n" for key in sorted(headers))
    canonical_uri = quote(parsed_url.path or "/", safe="/~")
    canonical_request = "\n".join(
        [
            method,
            canonical_uri,
            parsed_url.query,
            canonical_headers,
            signed_headers,
            payload_hash,
        ]
    )
    credential_scope = f"{date_stamp}/{region}/{service}/aws4_request"
    string_to_sign = "\n".join(
        [
            "AWS4-HMAC-SHA256",
            amz_date,
            credential_scope,
            hashlib.sha256(canonical_request.encode("utf-8")).hexdigest(),
        ]
    )
    signature = hmac.new(
        _signing_key(secret_access_key, date_stamp, region, service),
        string_to_sign.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    headers["Authorization"] = (
        "AWS4-HMAC-SHA256 "
        f"Credential={access_key_id}/{credential_scope}, "
        f"SignedHeaders={signed_headers}, "
        f"Signature={signature}"
    )
    return Request(url, data=body if method != "GET" else None, headers=headers, method=method)


def _build_signed_put_request(url, body, content_type, environ=os.environ, credentials=None):
    return _build_signed_request(
        "PUT",
        url,
        body=body,
        headers={"content-type": content_type},
        environ=environ,
        credentials=credentials,
    )


def upload_public_image_to_r2(source_path, cert_id, side, environ=os.environ, opener=urlopen):
    source_path = Path(source_path)
    if not source_path.is_file():
        raise FileNotFoundError(f"{side.title()} image file not found: {source_path.name}")

    endpoint = _env_value("R2_ENDPOINT", environ).rstrip("/")
    bucket = _env_value("R2_BUCKET", environ).strip("/")
    object_key = build_r2_object_key(cert_id, side, source_path, environ=environ)
    upload_url = f"{endpoint}/{quote(bucket, safe='')}/{quote(object_key, safe='/')}"
    public_url = build_r2_public_url(object_key, environ=environ)
    content_type = mimetypes.guess_type(source_path.name)[0] or "application/octet-stream"
    body = source_path.read_bytes()
    credentials = None
    if _is_truthy(environ.get("R2_USE_UPLOAD_ONLY_CREDENTIALS")):
        credentials = create_r2_upload_only_credentials(object_key, environ=environ)
    request = _build_signed_put_request(upload_url, body, content_type, environ=environ, credentials=credentials)

    try:
        with opener(request, timeout=30) as response:
            status_code = getattr(response, "status", response.getcode())
            if status_code >= 400:
                raise R2UploadError(f"R2 upload failed with HTTP {status_code}")
    except HTTPError as exc:
        detail = exc.read(500).decode("utf-8", errors="replace")
        raise R2UploadError(f"R2 upload failed with HTTP {exc.code}: {detail}") from exc

    return public_url
