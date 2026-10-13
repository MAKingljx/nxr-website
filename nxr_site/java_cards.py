"""Read-only compatibility lookup for certificates published by Java."""

import json
import os
import re
from urllib.parse import quote

import requests


CERT_ID = re.compile(r"[A-Za-z0-9_-]{3,64}\Z")
MAX_RESPONSE_BYTES = 128 * 1024


class JavaCardLookupUnavailable(Exception):
    """The published-card lookup could not give an authoritative answer."""


def published_card_redirect(cert_id):
    lookup = (cert_id or "").strip()
    base_url = os.environ.get("NXR_JAVA_PUBLIC_API_BASE", "").strip().rstrip("/")
    if not base_url or not CERT_ID.fullmatch(lookup):
        return None

    try:
        # Local Nginx follows the active Java slot. Do not inherit proxy settings
        # or follow upstream redirects, and never send a visitor's credentials.
        with requests.Session() as session:
            session.trust_env = False
            with session.get(
                f"{base_url}/api/public/cards/{quote(lookup, safe='')}",
                timeout=(1, 3),
                allow_redirects=False,
                stream=True,
                headers={"Accept": "application/json"},
            ) as response:
                if response.status_code == 404:
                    return None
                if response.status_code != 200:
                    raise JavaCardLookupUnavailable()
                chunks = []
                size = 0
                for chunk in response.iter_content(8192):
                    size += len(chunk)
                    if size > MAX_RESPONSE_BYTES:
                        raise JavaCardLookupUnavailable()
                    chunks.append(chunk)
                card = json.loads(b"".join(chunks))
    except (requests.RequestException, ValueError) as exc:
        raise JavaCardLookupUnavailable() from exc

    canonical_id = card.get("certId") if isinstance(card, dict) else None
    if (not isinstance(canonical_id, str) or not CERT_ID.fullmatch(canonical_id)
            or canonical_id.casefold() != lookup.casefold()):
        raise JavaCardLookupUnavailable()

    # Only this public endpoint returns published certificates. Build a fixed,
    # same-origin destination instead of trusting a stored QR URL or user URL.
    return f"/java-stage/card/{quote(canonical_id, safe='')}"
