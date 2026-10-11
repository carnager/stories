# Stories

An audiobook player for Android, for libraries kept in folders.

- **Folders are books.** Every folder with audio in it is a book; a folder of
  such folders is a series. Tags refine it: disc folders (`CD1`, `CD2`, or
  tagged disc numbers) join into one book, a folder holding two albums
  splits, and loose `.m4b` files are books of their own.
- **Known by their covers.** Grids of covers, each marked with how far it's
  heard: a dot while new, a bar while listening, a tick when finished. A
  series shows its covers stacked and how many are done.
- **Put right by hand** when the folders or tags don't say it: join books
  into one, make each file of a folder a book, keep disc folders apart, and
  edit title, author, reader and series. Kept by the app, never written to
  the files, and undone the same way; a book's place follows it through.
- **Remembers every book** on its own: where, at what speed, whether finished.
  A book that is moved or renamed keeps its place (found by its files).
- **Chapters** from MP4 (Nero `chpl` and QuickTime chapter tracks) and ID3
  `CHAP` frames; otherwise each file is a chapter.
- **Smart AudioBook Player** positions are taken over: the
  `position.sabp.dat` it leaves in each book folder is read on every scan,
  and a newer one wins, so both apps can be used side by side.
- **Synced libraries**: optionally, each book's place is also kept in a small
  `.stories-position.json` beside it, so a library synced between devices
  (Syncthing, Nextcloud) carries where you were; the newest place wins.
- **Chapters** also from CUE sheets and FLAC/Ogg/Opus `CHAPTERxx` comments.
- **Android Auto**: the books being heard and the folders, to browse in the car.
- **Widget**: the book being heard on the home screen, with back and play.
- Step back after a pause, per-book speed, sleep timer with fade-out and
  shake to start it over, bookmarks with notes, books under headings by author, series or reader,
  skip silence, lock screen, headphones
  and car controls that skip seconds rather than files.

Books are read straight from storage, so it asks for all-files access.
Android 11 or newer.

## License

GPL-3.0; see LICENSE. The Manrope font is under the SIL Open Font License.

## Building

```sh
./gradlew assembleDebug          # app/build/outputs/apk/debug
./gradlew testDebugUnitTest
./gradlew assembleRelease        # see SIGNING.md
```
