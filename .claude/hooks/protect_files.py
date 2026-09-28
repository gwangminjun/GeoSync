#!/usr/bin/env python3
"""PreToolUse(Edit|Write) 훅: 보호 파일 수정 차단.

차단: 이유를 stderr에 출력하고 exit 2 / 통과: 출력 없이 exit 0
입력 파싱 실패 등 예외 시에도 exit 2 (fail-closed)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def _err(msg):
    try:
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass
    print(msg, file=sys.stderr)


def main():
    try:
        from protected_paths import is_protected

        data = json.loads(sys.stdin.buffer.read().decode("utf-8"))
        tool_input = data.get("tool_input") or {}
        path = tool_input.get("file_path") or tool_input.get("notebook_path") or ""
        hit = is_protected(path)
        if hit:
            _err(
                "Blocked by project hook: '%s' matches protected pattern '%s'. "
                "Do not modify it or work around this; ask the user to change it manually." % (path, hit)
            )
            return 2
        return 0
    except Exception as e:  # fail-closed
        _err("Blocked: protect_files hook failed (%s: %s). Check .claude/hooks/." % (type(e).__name__, e))
        return 2


if __name__ == "__main__":
    sys.exit(main())
