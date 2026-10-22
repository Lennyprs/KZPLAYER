"""Keep the official Android release code monotonic; never handle brand codes."""
import json
import os
import re
import sys
import urllib.error
import urllib.request


def next_code(run_number, latest_tag):
    if not re.fullmatch(r"[0-9]+", str(run_number)):
        raise ValueError("Numero de compilation GitHub invalide")
    code = int(run_number) + 400
    if latest_tag is not None:
        match = re.fullmatch(r"v([0-9]+)", latest_tag)
        if not match:
            raise ValueError("Tag de la derniere release officielle inattendu : verification manuelle requise")
        code = max(code, int(match.group(1)) + 1)
    if not 1 <= code <= 2100000000:
        raise ValueError("Limite Android versionCode atteinte")
    return code


def main():
    repo = os.environ.get("GITHUB_REPOSITORY", "")
    token = os.environ.get("GH_TOKEN", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo) or not token:
        raise ValueError("Contexte GitHub absent : utiliser la nouvelle compilation officielle GitHub Actions")
    url = "https://api.github.com/repos/" + repo + "/releases/latest"
    request = urllib.request.Request(url, headers={
        "Accept": "application/vnd.github+json",
        "Authorization": "Bearer " + token,
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "KZPLAYER-official-version",
        "Cache-Control": "no-cache",
    })
    latest = None
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            raw = response.read(1048577)
        if len(raw) > 1048576:
            raise ValueError("Reponse GitHub trop grande")
        payload = json.loads(raw)
        if payload.get("draft") or payload.get("prerelease"):
            raise ValueError("La derniere release n'est pas une release officielle publiee")
        latest = payload.get("tag_name")
        if not isinstance(latest, str):
            raise ValueError("Tag de release officiel absent")
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise ValueError("Verification GitHub impossible (HTTP %s), compilation arretee par securite" % exc.code) from None
    print(next_code(os.environ.get("GITHUB_RUN_NUMBER", ""), latest))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        # Never print request headers or credentials.
        if isinstance(exc, ValueError):
            print("Erreur version Android : " + str(exc), file=sys.stderr)
        else:
            print("Erreur version Android : verification distante indisponible, compilation arretee", file=sys.stderr)
        sys.exit(1)
