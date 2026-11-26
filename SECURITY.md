# Security & Transparency Policy

Animated Capes is fully open source so players, server administrators, and moderators can inspect every line of code, verify all network requests, and reproduce release binaries directly from GitHub Actions.

---

## 1. Zero Session / Credential Access

Animated Capes **never** accesses, reads, stores, or transmits sensitive user data:
* Your Minecraft session token (`accessToken`) is **never** read or sent.
* Microsoft account credentials, Discord tokens, browser data, and system files outside your `.minecraft/animacapes` cape directory are **never** touched.
* All cloud cape sync operations are strictly opt-in / toggleable inside the in-game **Animated Capes Manager** (`Cloud: SYNC / OFF`). Disabling Cloud Sync runs the mod 100% offline.

---

## 2. Network Endpoints & Data Transmitted

All network code is isolated in the `com.jay.animacapes.cloud` and `com.jay.animacapes.command` packages:

| Action | Source File | Method & Endpoint | Data Sent |
| :--- | :--- | :--- | :--- |
| Upload / Sync Cape | `CloudCapeSync.java` | `POST /capes/<uuid>` | Selected cape image bytes (`.gif`/`.webp`/`.png`/`.jpg`), public player UUID, public username, SHA-256 hash of image, and filename |
| Remove Cloud Cape | `CloudCapeSync.java` | `DELETE /capes/<uuid>` | Public player UUID |
| Download Other Capes | `RemoteCapeManager.java` | `GET /capes/<uuid>` | Public player UUID |
| Copy Player Cape (`/cape wear`) | `CapeShareService.java` | `GET /capes/by-name/<player>` | Target player username |
| Moderation Report (`/cape report`) | `CapeShareService.java` | `POST /report` | Reported player name, reporter's public username/UUID, and report reason |

---

## 3. Embedded Helper & Native Libraries Audit

### A. Windows File Picker Helper (`cape_picker.exe`)
When selecting an image on Windows, opening a Win32 file dialog directly on Minecraft's GLFW render thread can freeze or crash the renderer when canceled. To prevent crashes, Animated Capes launches an isolated helper process (`tools/CapeFilePicker.cs`) that opens a standard Windows `OpenFileDialog`, prints the selected path to stdout, and exits.

* **Full Source Included**: [`tools/CapeFilePicker.cs`](tools/CapeFilePicker.cs) (35 lines of standard C# using `System.Windows.Forms.OpenFileDialog`, zero network calls).
* **SHA-256 of embedded `cape_picker.exe`**: `2e803f5c0b7399f8b14c1f7611968cc26867277684c62d36c773d1ed669a3c8e`

### B. Native Image Codecs (`src/main/resources/assets/animacapes/natives/windows-x64/`)
Animated WebP and high-efficiency decoding uses standard native libraries extracted at runtime from the mod resources:

| File | Library | SHA-256 |
| :--- | :--- | :--- |
| `libwebp-e5d4f9543eae6d381f6212f006b75bdc.dll` | Official Google `libwebp` decoder/encoder | `6ba6cc01fa80e05a63d59aec177e77d661c9d062356bd075b710c9622a6ba6a2` |
| `libwebpmux-c7c66c64054d42d6a25d9c92afeb2d3f.dll` | Official Google `libwebpmux` animation container parser | `67477a71150062e1bcaf5606e5ff9abf9a9b28aea61ed3d3c91b57d2f571a750` |
| `libsharpyuv-25eb0c4836aeeccd9b98aeb9ee77a148.dll` | Official Google `libsharpyuv` colorspace helper | `25eb0c4836aeeccd9b98aeb9ee77a148fdc794d9c32133f0abc5b26fb93b9159` |
| `libdav1d-d92ea30c3d9c9934f860c34d1ba70766.dll` | Official VideoLAN `dav1d` AV1 image decoder | `d92ea30c3d9c9934f860c34d1ba7076675153fc43888c8e3f910321cd146790c` |

---

## 4. Verifying Official Downloads

Every release built by GitHub Actions publishes a `SHA256SUMS.txt` file. You can verify any downloaded `.jar` on Windows with:

```cmd
CertUtil -hashfile AnimatedCapes-1.0.0+26.2.jar SHA256
```

Or on Linux / macOS:

```bash
sha256sum AnimatedCapes-1.0.0+26.2.jar
```

Compare the output against the matching release hash in the GitHub Releases page.

---

## 5. Reporting a Vulnerability

If you discover a security bug, please open a **Private Security Advisory** on GitHub under the **Security** tab rather than posting exploit details publicly.
