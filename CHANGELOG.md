# Changelog

## [0.7.0] — 2026-11-25

### Added

- CBZ/CBR comics: two-page spreads, natural page order, covers, page picker,
  saved progress and bounded decoding for large scans.
- TXT/Markdown creation and editing: numbered/wrapped source, offline Markdown
  preview, compact themed keyboard, D-pad cursor movement and undo/redo.
- Text selection on the larger screen with Copy/Paste/Select all, plain-text
  paste, Unicode handling and normalized Windows line endings.
- Recoverable text drafts, restored cursor positions, encoding preservation
  and protection against overwriting externally modified files.
- Library management: create/delete collections and documents, move files or
  collections, and import supported files into the current collection.
- Automatic physical font-size matching, with optional independent EPUB sizes
  behind **Allow different font sizes**.
- Four saved reading orientations and quick left/justified/right EPUB alignment.

### Changed

- **Library** is in the top reading quick menu.
- Reading menus and the shared keyboard follow the library theme.
- Notes have visible passage badges, clearer icons and a larger, numbered,
  vertically scrolling multiline editor.
- EPUB curls lift from the edge/corner, widen as they turn, and reflect the
  correct source text. Held page edges track the finger.
- Updated feature documentation and added a second, update-focused poster.

### Fixed

- Separate font controls continue to work at the shared size limit.
- Editor keyboard height and cursor/keyboard focus in all four orientations.
- Reversed text selections replace/delete correctly and participate in undo.
- Atomic text saves and draft recovery preserve originals and user backup files.
- Library moves finish safely if the app closes; imports verify copied data,
  avoid overwriting existing files and clean up interrupted copies on the next launch.
- Library controls fit sideways orientations.
- Rotation buttons display their direction icons correctly.

### Compatibility

- Maintainer-tested on Retroid Pocket Duo. The earlier EPUB release was reported
  to work on AYN Thor; the maintainer has not tested that device.
- Comic tests use generated CBZ and RAR 4/5 archives, corrupt-image fixtures
  and a 192-megapixel scan. Full real-world manga volumes need broader testing.
- Install over the previous version to keep app data. Re-select the library
  folder if write permission is needed. See the README for format limits.
- The next focus is polish, more testing and bug fixes.

## [0.6.1] — 2026-11-24

- First public release: offline dual-screen EPUB reading, library themes,
  matched page areas, page curls, search, bookmarks, highlights, notes,
  English dictionary and configurable button controls.

[0.7.0]: https://github.com/mollahsabbir/duoreader/releases/tag/v0.7.0
[0.6.1]: https://github.com/mollahsabbir/duoreader/releases/tag/v0.6.1
