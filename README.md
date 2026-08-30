# Account Audit — RuneLite plugin

Syncs your own quest completion, quest points, and skill levels to your Account Audit
profile, using the link-code ownership flow described in
[../docs/plugin-spec.md](../docs/plugin-spec.md).

## What it does / doesn't do

- **Reads only your own data**, and only data the vanilla client already shows you:
  quest states (`Quest.getState`), quest points (varp 101), real skill levels.
- **Sends nothing without consent**: syncing requires you to explicitly link via a code,
  and the "Sync progress data" toggle gates every send.
- **Never touches credentials.** You log in through RuneLite's own login flow; the
  plugin identifies your character by a SHA-256 of RuneLite's account hash — the raw
  value never leaves your machine. No automation, no gameplay interaction, no unfair
  advantage — read-and-report only, the same category as WikiSync / Wise Old Man /
  TempleOSRS, all of which are approved Plugin Hub plugins.

## Test it on your own account (dev client)

This is the standard RuneLite plugin-development workflow — a full, normal client with
this plugin loaded:

```bash
# from plugin/  (needs JDK 11+; Gradle bootstraps itself via the wrapper)
gradlew.bat runClient
```

### Jagex account? Do this first (one-time)

Characters on a Jagex Account can't log in from the in-client screen, so hand the dev
client your launcher session — the [officially documented](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) way:

1. Jagex Launcher → RuneLite → settings → **Client arguments**: add
   `--insecure-write-credentials` (launcher must be ≥ 2.6.3).
2. Launch RuneLite once via the Jagex Launcher and log in. It writes
   `~/.runelite/credentials.properties` (`C:\Users\<you>\.runelite\...` on Windows).
3. `gradlew.bat runClient` now logs straight in as that character.

**That file bypasses your password — treat it like one.** Never share or commit it;
delete it and remove the launcher argument when you're done developing. Legacy
(non-Jagex) accounts skip all of this and just log in on the client's login screen.

Then:

1. Run the web app (`npm run dev:web` from the repo root). Google OAuth is NOT required
   locally — with `AUTH_DEV_LOGIN=1` in `apps/web/.env.local` (already set for dev), the
   accounts page shows a "Dev login" button.
2. Sign in at http://localhost:3000/accounts (Dev login or Google) and click
   **Generate link code**.
3. In the dev RuneLite client, log into the character you want to link, open the
   Account Audit plugin settings, and paste the code into **Link code**.
   (Advanced → API base URL already defaults to `http://localhost:3000`.)
4. You'll get an in-game chat confirmation; within ~30 seconds the first sync lands and
   the website's **My accounts** page shows the character as synced.

## Building a jar

```bash
gradlew.bat build -x test    # -> build/libs/account-audit-0.1.0.jar
```

Distribution to other players goes through the RuneLite **Plugin Hub** (external
review; `runelite-plugin.properties` is the Hub manifest). Don't distribute raw jars —
sideloading random jars is exactly the behaviour users should distrust.

## Notes for maintainers

- Quest ids are the kebab-cased quest name (`Dragon Slayer I` → `dragon-slayer-i`),
  matching the web app's seed ids. Unknown ids are accepted server-side and triaged,
  so new quests degrade gracefully.
- Sync cadence: queued on login and every 5 minutes, delivered by a 30s ticker, skipped
  when nothing changed (payload digest) — and the server enforces its own 30s minimum.
- A 401 on sync means the link was revoked from the website; the plugin clears its
  token and asks the player to re-link.
