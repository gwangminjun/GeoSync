"""보호 대상 경로 판별 (protect_files.py, guard_bash.py 공용).

프로젝트에 맞게 아래 목록만 수정한다. 파일명 비교는 대소문자를 구분하지 않는다.
"""
import fnmatch

# 파일명(basename) 기준 보호 패턴 — 어디에 있든 이 이름/확장자면 차단
PROTECTED_NAMES = [
    ".env", ".env.*",
    "*.pem", "*.key", "*.p12", "*.pfx", "*.jks", "*.keystore",
    "id_rsa", "id_ed25519", "credentials.json",
]

# 보호 예외 (커밋용 템플릿 파일 등)
ALLOWED_NAMES = [".env.example", ".env.sample", ".env.template"]

# 경로 구성요소 기준 보호 디렉터리 (이 디렉터리 자체와 하위 전부)
PROTECTED_DIRS = [".git"]

# 프로젝트 특화: 경로 접미사 기준 보호 (basename만 보면 src/main/resources/application.yml
# 템플릿까지 막혀버리므로, conf/application.yml처럼 특정 위치의 파일만 지정한다)
PROTECTED_PATH_SUFFIXES = [
    "conf/application.yml",  # 실제 운영 DB 비밀번호 평문 포함 (gitignore 대상)
    "scripts/install-service.bat",
    "scripts/uninstall-service.bat",
]


def _normalize(path):
    p = path.strip().strip("'\"").replace("\\", "/")
    while "//" in p:
        p = p.replace("//", "/")
    return p.lower()


def is_protected(path):
    """보호 대상이면 매칭된 패턴 문자열, 아니면 None."""
    if not path:
        return None
    p = _normalize(path)
    parts = [x for x in p.split("/") if x]
    if not parts:
        return None
    name = parts[-1]

    for d in PROTECTED_DIRS:
        if d.lower() in parts:
            return d + "/"

    for suffix in PROTECTED_PATH_SUFFIXES:
        if p.endswith(suffix.lower()):
            return suffix

    if any(fnmatch.fnmatchcase(name, a.lower()) for a in ALLOWED_NAMES):
        return None
    for pattern in PROTECTED_NAMES:
        if fnmatch.fnmatchcase(name, pattern.lower()):
            return pattern
    return None
