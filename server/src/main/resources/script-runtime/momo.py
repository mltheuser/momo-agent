import json
import os
import urllib.error
import urllib.parse
import urllib.request

workspace: str = os.environ["MOMO_WORKSPACE"]


class Error(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code


def template(name: str) -> str:
    return _call("GET", f"/v1/templates/{urllib.parse.quote(name, safe='')}")["body"]


def create_session(
    *,
    title: str,
    harness: str,
    model: str,
    reasoning_effort: str,
    prompt: str,
    tool_models: dict[str, str] | None = None,
) -> str:
    session = _call(
        "POST",
        "/v1/sessions",
        {
            "title": title,
            "harnessPath": os.path.expanduser(harness),
            "workspace": workspace,
        },
    )["id"]
    try:
        _call(
            "POST",
            f"/v1/sessions/{session}/prompt",
            {
                "prompt": prompt,
                "model": model,
                "reasoningEffort": reasoning_effort,
                "toolModels": tool_models or {},
            },
        )
    except Error:
        _call("DELETE", f"/v1/sessions/{session}")
        raise
    return session


def _call(method: str, path: str, body: dict | None = None):
    request = urllib.request.Request(
        os.environ["MOMO_AGENT_URL"] + path,
        method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read() or "null")
    except urllib.error.HTTPError as refusal:
        error = json.loads(refusal.read())
        raise Error(error["code"], error["message"]) from None
