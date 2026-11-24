"""Build the bundled dictionary from a locally supplied WordNet 3.0 archive."""
import argparse
import gzip
from pathlib import Path
import shutil
import sqlite3
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parent.parent


def build(archive_path, output):
    output.mkdir(parents=True, exist_ok=True)
    work = ROOT / "build"
    work.mkdir(exist_ok=True)
    with tarfile.open(archive_path, "r:gz") as archive, tempfile.TemporaryDirectory(
        prefix="dictionary-", dir=work
    ) as temporary:
        def lines(name):
            with archive.extractfile("WordNet-3.0/" + name) as source:
                return source.read().decode("utf-8").splitlines()

        database = Path(temporary) / "dictionary.sqlite"
        db = sqlite3.connect(database)
        try:
            db.executescript(
                "PRAGMA page_size=4096;"
                "CREATE TABLE definitions(id INTEGER PRIMARY KEY,pos TEXT,gloss TEXT);"
                "CREATE TABLE words(word TEXT,sense INTEGER,rank INTEGER);"
                "CREATE TABLE exceptions(word TEXT,lemma TEXT);"
            )
            offsets = {}
            count = 0
            for kind in ("noun", "verb", "adj", "adv"):
                for line in lines("dict/data." + kind):
                    if not line or not line[0].isdigit():
                        continue
                    fields, gloss = line.split("|", 1)
                    tokens = fields.split()
                    count += 1
                    offsets[(kind, tokens[0])] = count
                    db.execute("INSERT INTO definitions VALUES(?,?,?)", (count, kind, gloss.strip()))
                for line in lines("dict/index." + kind):
                    if not line or line.startswith(" "):
                        continue
                    fields = line.split()
                    start = 6 + int(fields[3])
                    for rank, offset in enumerate(fields[start:start + int(fields[2])]):
                        identifier = offsets.get((kind, offset))
                        if identifier:
                            db.execute("INSERT INTO words VALUES(?,?,?)",
                                       (fields[0].replace("_", " "), identifier, rank))
                for line in lines("dict/" + kind + ".exc"):
                    forms = line.split()
                    for lemma in forms[1:]:
                        db.execute("INSERT INTO exceptions VALUES(?,?)",
                                   (forms[0].replace("_", " "), lemma.replace("_", " ")))
            db.executescript(
                "CREATE INDEX word_index ON words(word,rank);"
                "CREATE INDEX exception_index ON exceptions(word);PRAGMA user_version=1;"
            )
            db.commit()
            db.execute("VACUUM")
            assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            lemmas = db.execute("SELECT count(DISTINCT word) FROM words").fetchone()[0]
        finally:
            db.close()
        destination = output / "english-wordnet.sqlite.gz"
        compressed = Path(temporary) / "dictionary.gz"
        with database.open("rb") as source, compressed.open("wb") as raw:
            with gzip.GzipFile(fileobj=raw, filename="", mode="wb", compresslevel=9, mtime=0) as target:
                shutil.copyfileobj(source, target)
        with archive.extractfile("WordNet-3.0/LICENSE") as source:
            license_text = source.read()
        shutil.copyfile(compressed, destination)
        (output / "WORDNET-LICENSE.txt").write_bytes(license_text)
    print(f"Built {count:,} definitions and {lemmas:,} lemmas in {destination}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path, help="Path to WordNet-3.0.tar.gz")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "app/src/main/assets")
    args = parser.parse_args()
    build(args.archive, args.output_dir)


if __name__ == "__main__":
    main()
