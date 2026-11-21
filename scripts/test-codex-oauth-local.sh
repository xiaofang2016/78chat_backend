#!/usr/bin/env bash
# 本地验证 Codex OAuth：刷新 token + 调用 responses/compact（不打印 token 明文）
set -euo pipefail

AUTH_FILE="${OPENAI_CODEX_AUTH_FILE:-${CODEX_HOME:-$HOME/.codex}/auth.json}"
CLIENT_ID="${OPENAI_CODEX_CLIENT_ID:-app_EMoamEEZ73f0CkXaXp7hrann}"
TOKEN_URL="${OPENAI_CODEX_TOKEN_URL:-https://auth.openai.com/oauth/token}"
CODEX_BASE="${OPENAI_CODEX_BASE_URL:-https://chatgpt.com/backend-api/codex}"

if [[ ! -f "$AUTH_FILE" ]]; then
  echo "缺少 auth 文件: $AUTH_FILE（请先 codex login）"
  exit 1
fi

python3 - "$AUTH_FILE" "$CLIENT_ID" "$TOKEN_URL" "$CODEX_BASE" << 'PY'
import json, base64, sys, time, urllib.request, urllib.error
from pathlib import Path

auth_path = Path(sys.argv[1])
client_id = sys.argv[2]
token_url = sys.argv[3]
codex_base = sys.argv[4].rstrip("/")

data = json.loads(auth_path.read_text())
tokens = data.get("tokens") or {}
access = tokens.get("access_token") or ""
refresh = tokens.get("refresh_token") or ""
account = tokens.get("account_id") or ""

def jwt_exp(tok):
    try:
        p = tok.split(".")[1]
        p += "=" * (-len(p) % 4)
        return json.loads(base64.urlsafe_b64decode(p)).get("exp")
    except Exception:
        return None

exp = jwt_exp(access)
now = int(time.time())
print(f"[check] auth_mode={data.get('auth_mode')!r} account_id_set={bool(account)}")
if exp:
    print(f"[check] access_token_expired={now >= exp}")

def refresh_tokens():
    global access, refresh
    body = json.dumps({
        "client_id": client_id,
        "grant_type": "refresh_token",
        "refresh_token": refresh,
    }).encode()
    req = urllib.request.Request(token_url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        out = json.loads(resp.read().decode())
    if out.get("access_token"):
        access = out["access_token"]
    if out.get("refresh_token"):
        refresh = out["refresh_token"]
    data["tokens"] = {**tokens, "access_token": access, "refresh_token": refresh}
    if out.get("id_token"):
        data["tokens"]["id_token"] = out["id_token"]
    data["auth_mode"] = "chatgpt"
    data["last_refresh"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    auth_path.write_text(json.dumps(data, indent=2))
    print("[refresh] ok, auth.json updated")

if refresh and (exp is None or now >= exp - 30):
    try:
        refresh_tokens()
    except urllib.error.HTTPError as e:
        print(f"[refresh] failed http={e.code} body={e.read().decode()[:240]}")
        sys.exit(2)

payload = {
    "model": "gpt-5.5",
    "instructions": "Reply briefly.",
    "input": [{"type": "message", "role": "user", "content": "Say hello in three English words."}],
    "store": False,
    "max_output_tokens": 40,
}
headers = {
    "Authorization": f"Bearer {access}",
    "Content-Type": "application/json",
    "Accept": "application/json",
    "originator": "codex_cli_rs",
    "OpenAI-Beta": "responses=experimental",
}
if account:
    headers["ChatGPT-Account-ID"] = account

payload["stream"] = True
if "max_output_tokens" in payload:
    del payload["max_output_tokens"]

req = urllib.request.Request(
    f"{codex_base}/responses",
    data=json.dumps(payload).encode(),
    headers=headers,
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=60) as resp:
        raw = resp.read().decode()
    print(f"[codex] http={resp.status} body_len={len(raw)}")
    obj = json.loads(raw)
    preview = obj.get("output_text") or ""
    if not preview and obj.get("output"):
        for item in obj["output"]:
            if item.get("type") == "message":
                c = item.get("content")
                if isinstance(c, str):
                    preview = c
                elif isinstance(c, list):
                    for part in c:
                        if isinstance(part, dict) and part.get("text"):
                            preview = part["text"]
                            break
    if preview:
        print(f"[codex] preview={preview[:120]!r}")
    else:
        print(f"[codex] keys={list(obj.keys())[:8]}")
except urllib.error.HTTPError as e:
    print(f"[codex] failed http={e.code} body={e.read().decode()[:400]}")
    sys.exit(3)
PY

echo "Codex OAuth 本地链路测试通过"
