"""Opt-in force-stop recovery check against an already connected test device."""
import argparse
import hashlib
import shlex
import shutil
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit adb device serial")
    parser.add_argument("--adb", default=shutil.which("adb"), help="adb path, otherwise PATH")
    args = parser.parse_args()
    if not args.adb:
        parser.error("Install platform-tools and supply --adb or put adb on PATH")
    base = [args.adb, "-s", args.serial]

    def command(*parts):
        return subprocess.check_output(base + list(parts), text=True, encoding="utf-8", timeout=45)

    data = command("exec-out", "run-as", "io.duoreader", "cat", "shared_prefs/reader.xml")
    state = {item.attrib["name"]: item.attrib.get("value", item.text or "") for item in ET.fromstring(data)}
    if state.get("reading") != "true" or not state.get("lastUri"):
        parser.error("Open a book in the debug build before running this check")
    uri = state["lastUri"]
    prefix = "book.sha256." + hashlib.sha256(uri.encode("utf-8")).hexdigest() + "."
    for field in ["chapter", "offset"]:
        if prefix + field not in state:
            parser.error("Open the book in the current build before running this check")
    probe = ["am", "instrument", "-w", "-e", "uri", uri,
             "-e", "chapter", state[prefix + "chapter"],
             "-e", "offset", state[prefix + "offset"],
             "-e", "size", state["size"], "-e", "paper", state["paper"],
             "io.duoreader.tests/io.duoreader.ResumeProbe"]
    command("shell", "am", "force-stop", "io.duoreader")
    output = command("shell", " ".join(shlex.quote(part) for part in probe))
    if "RESUME_OK" not in output or "RESUME_FAILED" in output:
        raise SystemExit("Runtime recovery check failed:\n" + output)
    print("PASS: runtime restored the same book, chapter, character, font size and page color.")


if __name__ == "__main__":
    main()
