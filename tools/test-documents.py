"""Opt-in cold-start recovery checks using generated text and comic fixtures."""
import argparse
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=shutil.which("adb"))
    args = parser.parse_args()
    if not args.adb:
        parser.error("Install platform-tools and provide --adb")
    base = [args.adb, "-s", args.serial]

    def command(*parts):
        return subprocess.check_output(base + list(parts), text=True, encoding="utf-8", timeout=60)

    for document in ("Text", "Comic"):
        for phase in ("prepare", "verify"):
            if phase == "verify":
                command("shell", "am", "force-stop", "io.duoreader")
            output = command("shell", "am", "instrument", "-w", "-e", "stage", phase + document,
                             "io.duoreader.tests/io.duoreader.DocumentCheckpoint")
            print(output, end="", flush=True)
            if "DocumentCheckpoint_OK" not in output:
                raise SystemExit(f"{phase}{document} failed")
    print("PASS: drafts, cursor, comic pages, and internal orientation survive force-stop.")


if __name__ == "__main__":
    main()
