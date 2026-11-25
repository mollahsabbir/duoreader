# Third-party notices

## Markdown parser

CommonMark Java 0.33.0, Copyright 2015 Robin Stocker. BSD 2-Clause license.
Used for offline Markdown previews. The [full license](app/src/main/assets/licenses/commonmark.txt)
is included in the source and APK. Build dependencies are pinned with SHA-256 checksums.
Source: [commonmark-java](https://github.com/commonmark/commonmark-java).

## Comic archives

Junrar 8.1.2 reads RAR/CBR archives, under the [UnRAR license](app/src/main/assets/licenses/junrar.txt).
It permits use and distribution for extraction, and prohibits recreating RAR compression.
Its logging dependency, SLF4J API 2.0.17, uses the [MIT license](app/src/main/assets/licenses/slf4j.txt).
Both notices are included in the APK. Sources: [Junrar](https://github.com/junrar/junrar),
[SLF4J](https://www.slf4j.org/).

## Offline dictionary

WordNet 3.0, Copyright 2006 Princeton University. All rights reserved.
The compressed database in `app/src/main/assets/english-wordnet.sqlite.gz`
contains indexed lexical data used by the offline dictionary. Its complete
[WordNet license](app/src/main/assets/WORDNET-LICENSE.txt) is retained in the
source and APK. It is separate from the application's MIT license.

Source: [WordNet 3.0 archive](https://wordnetcode.princeton.edu/3.0/WordNet-3.0.tar.gz).
Rebuild with `python tools/build-dictionary.py PATH_TO_ARCHIVE`.

## Poster

`docs/poster.png` uses [Retroid's Pocket Duo product imagery](https://www.goretroid.com/products/retroid-pocket-duo-handheld)
with actual app screenshots inserted programmatically. Retroid's imagery and
trademarks are not covered by the code's MIT license. No affiliation or
endorsement is implied.

The displayed text, illustrations and covers are from [Standard Ebooks](https://standardebooks.org/):
Alice's Adventures in Wonderland, The Time Machine, The War of the Worlds,
and Journey to the Centre of the Earth. Source text/artwork is public domain
in the U.S.; Standard Ebooks' original contributions use [CC0](https://standardebooks.org/about/standard-ebooks-and-the-public-domain).
No book files or embedded book fonts are distributed with this repository or APK.
