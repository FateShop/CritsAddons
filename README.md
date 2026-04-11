# CritsAddons 1.21.10

Addon features for NoammAddons on Minecraft 1.21.10.

## What Is This

CritsAddons is an addon mod that extends NoammAddons with extra client-side dungeon/QoL features.

## Install

1. Install [Fabric for Minecraft 1.21.10](https://fabricmc.net/use/installer/).
2. Install [Fabric API](https://modrinth.com/mod/fabric-api).
3. Install [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
4. Install NoammAddons for Minecraft `1.21.10` from the [NoammAddons releases page](https://github.com/Noamm9/NoammAddons/releases).
5. Download the latest CritsAddons `.jar` from the [CritsAddons releases page](https://github.com/fateshop/CritsAddons/releases).
6. Put both `.jar` files in `.minecraft/mods`.
7. Launch Minecraft with your Fabric profile.

NoammAddons is required. CritsAddons does not run standalone.

## Features

- Party Finder additions
- Party HUD
- Secret Routes
- Secret Routes Debugger
- Persistent Secret Heads
- Zoom
- Auto LCM

## Secret Routes Commands

Use commands while inside a scanned dungeon room.

- `/nsr`  
  Start recording the main route (requires standing centered on the start block).
- `/nsr save`  
  Save the active main route recording.
- `/nsr cancel`  
  Cancel active `/nsr` or `/nsr start` recording.
- `/nsr delete`  
  Delete the room's saved route.
- `/nsr start`  
  Start one-link start recording (requires centered block position). Do exactly one Etherwarp from your current start block to an existing known start block. It auto-saves after a valid link.
- `/nsr start delete`  
  While centered on a non-original start block, delete that start link and dependent links that route through it.
- `/nsr wait`  
  Insert a wait-for-secret-progress step in the active `/nsr` recording.
- `/nsr bat`  
  Insert a wait-for-bat-spawn step in the active `/nsr` recording.
- `/nsr kill`  
  Pause `/nsr` recording, right-click ground with Hyperion, then resume recording.

## Secret Routes Playback Notes

- Playback starts from the Secret Routes playback keybind.
- Auto-start supports start-block-only mode, center-only checks, center hold time, and center radius.
- Start route from anywhere supports step resume from centered recorded step blocks.
- Start-link chains are followed before route-step resume logic.
- Routes file is selectable via `Routes Config File`.
- `Reload Routes File` reloads the selected JSON without restarting the game.

## Party HUD Notes

- Party snapshot now updates from party chat events (`/p list`, joins, leaves, removes).
- `Clear Cache` button clears HUD/profile caches and forces a short live refetch window.

## Zoom Notes

- Hold the zoom keybind to zoom in.
- Scroll while zoomed to adjust zoom amount.
- Rotation smoothing while zoomed is controlled by `Rotation Smoothness` (`0` = instant, `100` = very smooth/slow).

## Auto LCM Notes

- While in dungeons as Mage, holding left click auto-triggers LCM left clicks.
- Delay is randomized between `Min Delay (ticks)` and `Max Delay (ticks)` (default 4 to 6 ticks).

## Dependency Update Automation

If NoammAddons updates, you can sync `noammaddons_version` in `gradle.properties` with Gradle tasks.

- Manual set:  
  `./gradlew setNoammAddonsVersion -PnoammVersion=<hash-or-tag>`
- Auto-sync latest commit from upstream branch:  
  `./gradlew syncNoammAddonsVersion`

Optional overrides for auto-sync:

- `-PnoammBranch=<branch>` (defaults to `noammaddons_type`, usually `cheat`)
- `-PnoammShaLength=<7-40>` (default `10`)
- `-PnoammRepoOwner=<owner>` and `-PnoammRepoName=<repo>` (defaults: `Noamm9`, `NoammAddons`)

Windows example:

`.\\gradlew.bat syncNoammAddonsVersion -PnoammBranch=cheat`

## Build

- `./gradlew build`

## Contributions

- Open an issue for bug reports or feature requests.
- Open a pull request for fixes or additions.

## License

This project is licensed under [LICENSE.txt](LICENSE.txt).
