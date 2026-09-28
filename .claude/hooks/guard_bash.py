#!/usr/bin/env python3
"""PreToolUse(Bash|PowerShell) 훅: 파괴적 명령과 보호 파일 쓰기 차단.

차단: 이유를 stderr에 출력하고 exit 2 / 통과: 출력 없이 exit 0
입력 파싱 실패 등 예외 시에도 exit 2 (fail-closed)

차단 기본값 (프로젝트에 맞게 수정):
  - rm 재귀+강제 삭제 (rm -rf, rm -r -f, rm --recursive --force, find -exec rm -rf 등)
  - git push 강제 푸시 (--force, -f, +refspec). --force-with-lease 는 허용
  - SQL DROP TABLE/DATABASE/SCHEMA, TRUNCATE (conf/sql/*.sql이 운영 DB에 직접 실행됨)
  - PowerShell Remove-Item -Recurse
  - 보호 파일(protected_paths.py)에 대한 리다이렉트(>, >>)와 rm/mv/tee/sed -i/cp 대상 쓰기
"""
import json
import os
import re
import shlex
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# 명령 문자열 전체에 적용하는 정규식 (대소문자 무시)
BLOCK_REGEX = [
    (r"\bdrop\s+(table|database|schema)\b", "SQL DROP"),
    (r"\btruncate\s+(table\s+)?[a-z_\"`\[]", "SQL TRUNCATE"),
    (r"\bremove-item\b[^;|\n]*\s-r(ecurse)?\b", "PowerShell Remove-Item -Recurse"),
]

SKIP_PREFIX = {"sudo", "env", "command", "nohup", "time", "exec"}
ANY_ARG_WRITE_CMDS = {"rm", "mv", "tee", "truncate", "chmod", "chown", "shred", "unlink"}
LAST_ARG_WRITE_CMDS = {"cp", "install", "ln"}
SEGMENT_SPLIT = re.compile(r"\|\||&&|[;|\n]")
REDIRECT = re.compile(r"(?:\d?>>?|&>)\s*([^\s;&|<>]+)")


def _err(msg):
    try:
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass
    print(msg, file=sys.stderr)


def _clean(token):
    return re.sub(r"^[$(`\"']+|[)`\"']+$", "", token)


def _tokens(segment):
    try:
        return shlex.split(segment, posix=True)
    except ValueError:
        return segment.split()


def _is_short_flag(t):
    return t.startswith("-") and not t.startswith("--") and len(t) > 1


def _check_rm(args):
    flags = []
    for a in args:
        if a == "--":
            break
        if a.startswith("-"):
            flags.append(a)
    recursive = any(f == "--recursive" or (_is_short_flag(f) and re.search("[rR]", f[1:])) for f in flags)
    force = any(f == "--force" or (_is_short_flag(f) and "f" in f[1:]) for f in flags)
    return recursive and force


def _check_git_push(args):
    for a in args:
        if a == "--force" or a.startswith("--force="):
            return True
        if _is_short_flag(a) and "f" in a[1:]:
            return True
        if a.startswith("+") and len(a) > 1:
            return True
    return False


def analyze(command, is_protected, depth=0):
    """차단 사유 문자열 또는 None."""
    if depth > 3 or not command.strip():
        return None

    for pattern, label in BLOCK_REGEX:
        if re.search(pattern, command, re.IGNORECASE):
            return label

    for segment in SEGMENT_SPLIT.split(command):
        for target in REDIRECT.findall(segment):
            hit = is_protected(_clean(target))
            if hit:
                return "redirect into protected file '%s' (%s)" % (target, hit)

        raw = _tokens(segment)
        toks = [_clean(t) for t in raw]

        # 따옴표로 감싼 하위 명령 (bash -c "..." 등) 재귀 검사
        for t in raw:
            if " " in t.strip():
                reason = analyze(t, is_protected, depth + 1)
                if reason:
                    return reason

        for i, t in enumerate(toks):
            base = os.path.basename(t)
            if base == "rm" and _check_rm(toks[i + 1:]):
                return "recursive force delete (rm -rf)"
            if base == "git" and "push" in toks[i + 1:]:
                push_at = toks.index("push", i + 1)
                if _check_git_push(toks[push_at + 1:]):
                    return "git force push (use --force-with-lease only if the user asks)"

        # 명령어 위치 찾기 (sudo, VAR=val 등 건너뜀)
        idx = 0
        while idx < len(toks) and (toks[idx] in SKIP_PREFIX or re.match(r"^\w+=", toks[idx])):
            idx += 1
        if idx >= len(toks):
            continue
        cmd = os.path.basename(toks[idx])
        args = [a for a in toks[idx + 1:] if not a.startswith("-")]

        targets = []
        if cmd in ANY_ARG_WRITE_CMDS:
            targets = args
        elif cmd in LAST_ARG_WRITE_CMDS and args:
            targets = args[-1:]
        elif cmd in ("sed", "perl") and any(a.startswith("-i") for a in toks[idx + 1:]):
            targets = args
        elif cmd == "dd":
            targets = [a[3:] for a in args if a.startswith("of=")]

        for a in targets:
            hit = is_protected(a)
            if hit:
                return "%s on protected file '%s' (%s)" % (cmd, a, hit)
    return None


def main():
    try:
        from protected_paths import is_protected

        data = json.loads(sys.stdin.buffer.read().decode("utf-8"))
        command = (data.get("tool_input") or {}).get("command") or ""
        reason = analyze(command, is_protected)
        if reason:
            _err(
                "Blocked by project hook: %s. Do not retry or work around this; "
                "explain what you intended and ask the user to run it manually if needed." % reason
            )
            return 2
        return 0
    except Exception as e:  # fail-closed
        _err("Blocked: guard_bash hook failed (%s: %s). Check .claude/hooks/." % (type(e).__name__, e))
        return 2


if __name__ == "__main__":
    sys.exit(main())
