<div align="center">

# 💫 Animated Capes
### High-FPS Animated & HD Capes for Minecraft | Global Cloud Sync | Zero Lag

**Animated Capes** lets you wear custom animated GIF, WebP, and high-definition capes in Minecraft with **zero FPS loss**, **actively playing gallery previews**, and **automatic global multiplayer cloud sync** across all servers! (ANYONE WITH THE MOD CAN VIEW YOUR CAPE!)

</div>

---

## 🌟 Key Features

* **🎬 High-FPS Smooth Animations:** Full support for animated `.gif` and `.webp` files, animating at up to 60+ FPS with buttery-smooth interpolation and custom speed scaling.
* **🌐 Global Multiplayer Cloud Sync:** Play on **Hypixel, public SMPs, Realms, LAN, or Singleplayer** — anyone with the mod sees your cape animating on your back in real time!
* **⚡ Zero-CPU VRAM Swapping:** Designed for competitive PvP and low-end PCs alike. Cape frames swap directly inside GPU texture memory, resulting in **0.0% CPU overhead** and zero frame drops.
* **🎞️ Actively Playing Gallery Previews:** The in-game Cape Gallery features live, actively animating previews on every card. See your capes moving in real time right in the selection grid!
* **⏱️ Dynamic Animation Speed Slider:** Control your cape animation speed with an in-game slider ranging from **0.25x (Slow-Motion)** to **3.00x (Hyper)**, featuring a tactile center detent that snaps to **1.00x (Normal)**.
* **📄 Multi-Page Gallery with Scroll Wheel:** Responsive pagination automatically calculates card layouts for any GUI scale or resolution. Browse pages with `◀ Prev Page`, `Next Page ▶`, or your mouse scroll wheel!
* **🗑️ Interactive Cape Removal (`[✕]`):** Remove unwanted capes directly from the gallery with a built-in 2-step `[SURE?]` safety confirmation that prevents accidental clicks. If active, it cleanly un-equips and frees GPU VRAM instantly.
* **🔍 Search & Wear Any Player's Cape:** Love someone's cape on a server? Use `/cape wear <player>` or click **"Wear Player..."** in the gallery to search any username. The mod automatically downloads their active animated cape into your gallery and equips it immediately!
* **🚨 In-Game Reporting:** Encountered someone wearing an inappropriate or NSFW cape? Report them via `/cape report <player> [reason]` or the in-game GUI.
* **🚫 Player Blocklist (Visual Suppression):** Block specific users with `/cape block <player>` (or in GUI) to completely suppress and hide their cape on your screen. You will never see capes from players on your blocklist.
* **🚀 100% Non-Blocking Async Uploads:** Uploading large GIFs or browsing files never freezes your game. Native OS file pickers and frame rasterization run on dedicated background threads so Minecraft never halts or shows *(Not Responding)*.
* **🛡️ Smart WebP Optimization Guard:** Static images are losslessly optimized to reduce bandwidth. If a GIF compresses better than WebP, the optimizer preserves the original file without size ballooning.
* **💎 Multiple Fit Modes:** Switch between **Cover** (edge-to-edge full bleed), **Contain** (proportion-preserving letterbox), and **Stretch** on the fly.
* **🛡️ Client-Side Only:** Servers do **not** need the mod installed. You can use it anywhere without permission errors or server plugins.

---

## 🖼️ In-Game Cape Manager & Gallery (`H` Key)

Press **`H`** on your keyboard at any time in-game to open the **Animated Capes Manager**:

1. **Live Preview Card:** Displays your currently active cape file, frame count, FPS, engine state, and real-time Cloud Sync status.
2. **Cape Gallery Button:** Opens the full animated gallery grid featuring live-playing animated cards.
3. **Speed Slider (0.25x – 3.00x):** Fine-tune animation speed on the fly with tactile center-snap at 1.00x.
4. **Fit Mode Switcher:** Toggle between Cover, Contain, and Stretch with instant visual updates.
5. **Quick-Cycle Controls (`◀` / `▶`):** Cycle through all capes in your collection directly from the main status screen.
6. **Open Folder Button:** One click opens your `.minecraft/animacapes/` folder in Windows Explorer / Finder / file manager.

---

## 📊 Supported Formats & File Limits

| Format | Max File Size | Animation Support | Notes |
| :--- | :---: | :---: | :--- |
| **WebP** (`.webp`) | **5 MB** *(20 MB Whitelisted)* | ✅ Yes | Highest quality & best compression. *(Animated WebP requires whitelist)* |
| **GIF** (`.gif`) | **10 MB** | ✅ Yes | Open to everyone! Drop any Discord sticker or animated GIF directly. |
| **PNG** (`.png`) | **5 MB** | ❌ Static Only | Pure 100% mathematically lossless compression. *(APNG rejected)* |
| **JPG / JPEG** (`.jpg`) | **5 MB** | ❌ Static Only | HD wallpaper & photo capes supported with 0 quality loss. |

---

## 🎮 How to Use

1. Press **`H`** on your keyboard in-game to open the **Animated Capes Manager**.
2. Click **"Choose Animation (WebP / GIF)..."** to pick a file asynchronously without freezing your client, or click **"Open Folder"** and drag files into `animacapes/`.
3. Open **"Cape Gallery"** to view your collection in live, actively playing preview cards.
4. Adjust the **Speed Slider** to your preferred playback rate (0.25x to 3.00x).
5. Click any cape card to immediately equip it and sync it across multiplayer!

---

## 👑 In-Game Commands 

| Command | Description | Permission |
| :--- | :--- | :---: |
| `/cape wear <player>` | Search, download & equip any other player's active cape | Public |
| `/cape report <player> [reason]` | Report an inappropriate cape with embed forwarded to Discord | Public |
| `/cape block <player>` | Block a player so you never see their cape on your screen | Public |
| `/cape unblock <player>` | Unblock a player | Public |
| `/cape blocklist` | View your list of blocked users | Public |
| `/cape help` | Show all available commands in chat | Public |

---

## ❓ Frequently Asked Questions (FAQ)

<details>
<summary><b>Does this mod cause FPS drops or lag in multiplayer?</b></summary>
<br>
<b>No!</b> Animated Capes uses a specialized Zero-CPU VRAM texture caching pipeline. Cape animation frames are rendered directly into GPU memory buffers, so your CPU usage remains at 0% even with dozens of animated capes on screen at once.
</details>

<details>
<summary><b>Why did my game previously freeze when uploading large files?</b></summary>
<br>
In older cape mods, file browsing and frame rasterization ran on the main game thread, blocking Minecraft until finished. In Animated Capes, all OS dialogs, file copying, and bicubic frame rasterization run on dedicated background threads, keeping your frame rate smooth and responsive at all times.
</details>

<details>
<summary><b>Do other players need the mod to see my cape?</b></summary>
<br>
Yes, any player who has the <b>Animated Capes</b> mod installed will see your cape moving in real time. Players who do not have the mod will see your standard Minecraft cape (or none if you haven't equipped one).
</details>

<details>
<summary><b>Does the server need to install this mod?</b></summary>
<br>
<b>No!</b> Animated Capes is 100% client-side. You can install it on your client and join Hypixel, Mineplex, SMPs, or LAN worlds without the server needing any plugins or mods.
</details>

<details>
<summary><b>What happens if I change my Minecraft username?</b></summary>
<br>
Nothing is lost! The system resolves your account by your permanent Mojang UUID (Universally Unique Identifier). If you change your name, your cape and whitelist privileges stay with you automatically.
</details>

---

## 📦 Requirements & Installation

1. Install **[Fabric Loader](https://fabricmc.net/)** for your Minecraft version.
2. Download and drop **[Fabric API](https://modrinth.com/mod/fabric-api)** into your `.minecraft/mods` folder.
3. Download the **Animated Capes** JAR for your version (`1.21.11`, `26.1`, or `26.2`) and place it in `.minecraft/mods`.
4. *(Optional)* Install **[ModMenu](https://modrinth.com/mod/modmenu)** to configure settings directly from the in-game mod list!
