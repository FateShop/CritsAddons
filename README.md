# CritsAddons 1.21.10

> Addon features for NoammAddons on Minecraft 1.21.10.

----

## What is this?

CritsAddons is an addon mod built on top of **NoammAddons**.  
It adds extra client-side features while using NoammAddons as its base dependency.

Current features include:

* Party Finder
* Party HUD
* Secret Routes
* Persistent secret head rendering

----

## How to install?

1. Install **[Fabric for Minecraft 1.21.10](https://fabricmc.net/use/installer/)**
2. Install **[Fabric API](https://modrinth.com/mod/fabric-api)** and **[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)**
3. Install **NoammAddons** for Minecraft `1.21.10`[**Releases page**](https://github.com/Noamm9/NoammAddons/releases)
4. Download the latest **CritsAddons** `.jar` from the [**Releases page**](https://github.com/fateshop/CritsAddons/releases)
5. Put both `.jar` files into your `.minecraft/mods` folder
6. Launch Minecraft using the **Fabric** profile

* Keep **NoammAddons** installed. CritsAddons depends on it and will not work by itself.
* Most settings are available through the NoammAddons config UI.
* The latest downloadable builds are available on the [**Releases page**](https://github.com/fateshop/CritsAddons/releases).

----

## Features

CritsAddons currently includes:

* **Party Finder** additions
* **Party HUD**
* **Secret Routes**
* **Persistent secret heads** for easier route recording

----

## Secret Routes Commands

Use `/nsr` in a scanned dungeon room:

* `/nsr`  
  Start recording the main route for the current room.
* `/nsr save`  
  Save the current recording.
* `/nsr cancel`  
  Cancel the current recording without saving.
* `/nsr delete`  
  Delete the saved route for the room you are currently in.
* `/nsr start`  
  Start recording an alternate start path for a room that already has a main route.
* `/nsr wait`  
  Insert a wait-for-secret-progress step into the active recording.
* `/nsr bat`  
  Insert a wait-for-bat-spawn step into the active recording.
* `/nsr kill`  
  Temporarily pauses recording, uses Hyperion on the ground, then resumes recording.

Playback:

* Playback is started with the **Secret Routes Playback Keybind**.
* If enabled in settings, standing in the center on a valid start block can auto-start playback (Ether warp to start block).

----

## Dependency Update Automation

If NoammAddons updates, you can update this project in one command instead of editing `gradle.properties` manually.

Commands:

* Manual set:  
  `gradle setNoammAddonsVersion -PnoammVersion=<hash-or-tag>`
* Auto-sync latest commit from upstream branch:  
  `gradle syncNoammAddonsVersion`

Optional overrides for auto-sync:

* `-PnoammBranch=<branch>` (defaults to `noammaddons_type`, usually `cheat`)
* `-PnoammShaLength=<7-40>` (default `10`)
* `-PnoammRepoOwner=<owner>` and `-PnoammRepoName=<repo>` (defaults: `Noamm9`, `NoammAddons`)

Example:

`gradle syncNoammAddonsVersion -PnoammBranch=cheat && gradle build`

----

## Contributions

Contributions are welcome. If you want to add a feature, port an existing one, or fix a bug, feel free to open an issue or submit a pull request.

* Open an **Issue** to suggest something
* Make a **Pull Request** to add or fix stuff

----

## License

This project is licensed under the [**LICENSE.txt**](LICENSE.txt).
