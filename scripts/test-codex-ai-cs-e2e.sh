#!/usr/bin/env bash
# 端到端验证 AI 客服 Codex 请求体（与 OpenAiChatCompletionClient 一致）
# 用法: OPENAI_CODEX_AUTH_FILE=/path/to/auth.json ./scripts/test-codex-ai-cs-e2e.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
AUTH_FILE="${OPENAI_CODEX_AUTH_FILE:-${CODEX_HOME:-$HOME/.codex}/auth.json}"
CODEX_BASE="${OPENAI_CODEX_BASE_URL:-https://chatgpt.com/backend-api/codex}"
CLIENT_ID="${OPENAI_CODEX_CLIENT_ID:-app_EMoamEEZ73f0CkXaXp7hrann}"
TOKEN_URL="${OPENAI_CODEX_TOKEN_URL:-https://auth.openai.com/oauth/token}"

if [[ ! -f "$AUTH_FILE" ]]; then
  echo "缺少 auth: $AUTH_FILE"
  exit 1
fi

echo "[1/3] unit tests (Codex wire + KB guard)"
(cd "$ROOT/im-platform" && mvn -q test -Dtest=OpenAiChatCompletionClientCodexModelTest,AiKnowledgeBaseServiceImplKbGuardTest)

if [[ "${SKIP_CODEX_LIVE:-0}" == "1" ]]; then
  echo "[2/3] SKIP_CODEX_LIVE=1，跳过线上 Codex 调用"
  echo "全部通过（仅单元测试）"
  exit 0
fi

echo "[2/3] Codex SSE: 活动咨询 + flatten tools（无 max_output_tokens / tool_choice）"
python3 - "$AUTH_FILE" "$CLIENT_ID" "$TOKEN_URL" "$CODEX_BASE" << 'PY'
import json, base64, sys, time, urllib.request, urllib.error
from pathlib import Path

auth_path = Path(sys.argv[1])
client_id, token_url, codex_base = sys.argv[2:5]
codex_base = codex_base.rstrip("/")
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
if refresh and (exp is None or now >= exp - 30):
    body = json.dumps({"client_id": client_id, "grant_type": "refresh_token", "refresh_token": refresh}).encode()
    req = urllib.request.Request(token_url, data=body, headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        out = json.loads(resp.read().decode())
    access = out.get("access_token") or access
    print("[refresh] ok")

def flatten_tool(name, description):
    return {
        "type": "function",
        "name": name,
        "description": description,
        "parameters": {"type": "object", "properties": {}, "required": []},
        "strict": False,
    }

def user_item(text):
    return {
        "type": "message",
        "role": "user",
        "content": [{"type": "input_text", "text": text}],
    }

def parse_sse(raw: str):
    text_parts = []
    completed = None
    output_items = []
    for line in raw.splitlines():
        if not line.startswith("data:"):
            continue
        payload = line[5:].strip()
        if not payload or payload == "[DONE]":
            continue
        try:
            evt = json.loads(payload)
        except json.JSONDecodeError:
            continue
        t = evt.get("type")
        if t == "response.output_text.delta":
            d = evt.get("delta") or ""
            if d:
                text_parts.append(d)
        elif t in ("response.completed", "response.done"):
            completed = evt.get("response") or evt
        elif t == "response.output_item.done":
            item = evt.get("item")
            if item:
                output_items.append(item)
    def merge_completed(completed_obj, items):
        if completed_obj is None:
            return {"output": items} if items else {}
        merged = dict(completed_obj)
        out = merged.get("output") or []
        if not out and items:
            merged["output"] = items
        elif items:
            combined = list(out)
            seen = set()
            for it in items:
                key = (it.get("type"), it.get("call_id") or it.get("id"))
                if key in seen:
                    continue
                seen.add(key)
                combined.append(it)
            merged["output"] = combined
        return merged

    if completed is not None:
        completed = merge_completed(completed, output_items)
        return completed, "".join(text_parts)
    out = {"output": output_items} if output_items else {}
    if text_parts:
        out["output_text"] = "".join(text_parts)
    return out, "".join(text_parts)

def codex_post(body):
    headers = {
        "Authorization": f"Bearer {access}",
        "Content-Type": "application/json",
        "Accept": "text/event-stream, application/json",
        "originator": "codex_cli_rs",
        "OpenAI-Beta": "responses=experimental",
    }
    if account:
        headers["ChatGPT-Account-ID"] = account
    req = urllib.request.Request(
        f"{codex_base}/responses",
        data=json.dumps(body).encode(),
        headers=headers,
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        raw = resp.read().decode()
    return raw, parse_sse(raw)

# --- 场景 A：纯文本（无 tools）---
body_a = {
    "model": "gpt-5.5",
    "instructions": "你是 78CHAT 客服，用中文简短回答。",
    "input": [user_item("用一句话说你好")],
    "store": False,
    "stream": True,
}
try:
    raw_a, (obj_a, preview_a) = codex_post(body_a)
    print(f"[A] sse_len={len(raw_a)} preview={preview_a[:80]!r}")
    if not preview_a and not (obj_a.get("output") or obj_a.get("output_text")):
        print("[A] FAIL empty response")
        sys.exit(10)
except urllib.error.HTTPError as e:
    print(f"[A] FAIL http={e.code} {e.read().decode()[:500]}")
    sys.exit(11)

# --- 场景 B：带 flatten tools（模拟「有什么活动」）---
tools = [
    flatten_tool("get_server_time", "查询服务器时间"),
    flatten_tool("get_available_activities", "查询用户可参与的活动列表"),
]
body_b = {
    "model": "gpt-5.5",
    "instructions": "你是 78CHAT 客服。用户问活动时优先说明可参与活动，可调用工具。",
    "input": [user_item("有什么活动")],
    "store": False,
    "stream": True,
    "tools": tools,
}
try:
    raw_b, (obj_b, preview_b) = codex_post(body_b)
    output = obj_b.get("output") or []
    fn_calls = [x for x in output if x.get("type") == "function_call"]
    print(f"[B] sse_len={len(raw_b)} text_preview={preview_b[:80]!r} function_calls={len(fn_calls)}")
    if fn_calls:
        fc = fn_calls[0]
        call_id = fc.get("call_id") or fc.get("id")
        name = fc.get("name")
        args = fc.get("arguments") or "{}"
        print(f"[B] tool_call name={name!r} call_id={call_id!r}")
        # --- 场景 C：tool 回传第二轮 ---
        body_c = {
            "model": "gpt-5.5",
            "instructions": "你是 78CHAT 客服。根据工具结果用中文回答。",
            "input": [
                user_item("有什么活动"),
                {"type": "function_call", "call_id": call_id, "name": name, "arguments": args},
                {
                    "type": "function_call_output",
                    "call_id": call_id,
                    "output": json.dumps({"ok": True, "data": {"activity_count": 3, "names": ["A", "B", "C"]}}, ensure_ascii=False),
                },
            ],
            "store": False,
            "stream": True,
        }
        raw_c, (obj_c, preview_c) = codex_post(body_c)
        print(f"[C] round2 preview={preview_c[:120]!r}")
        out_c = obj_c.get("output") or []
        msg_c = [x for x in out_c if x.get("type") == "message"]
        if not preview_c and not msg_c:
            print("[C] FAIL round2 empty")
            sys.exit(14)
    elif not preview_b:
        print("[B] FAIL no text and no tool_calls (check SSE merge)")
        sys.exit(12)
except urllib.error.HTTPError as e:
    print(f"[B] FAIL http={e.code} {e.read().decode()[:500]}")
    sys.exit(13)

print("[3/3] Codex AI-CS e2e OK")
PY

echo "全部通过"
