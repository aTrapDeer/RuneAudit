# RuneAudit Plugin — Scope & Data Access

This document states exactly what the plugin does, what it reads from the client, what
it transmits, and what it will never do. It exists for Plugin Hub reviewers and for
users deciding whether to install.

## What the plugin is

A companion to the RuneAudit website. It lets a player link their character to
their own web account (proving ownership without credentials) and syncs their progress
so the site can build personalised plans, roadmaps, and recommendations. A side panel
shows the player's current quest-route progress and one suggestion.

## What it reads from the client

| Data | Source | When |
|---|---|---|
| Quest states (~195 quests) | `Quest.getState()` | on login + every 5 min |
| Quest points | varp 101 | same |
| Skill levels | `getRealSkillLevel()` | same |
| Worn equipment (item id/name/qty) | equipment container | same |
| Achievement diary tier completion (48 tiers) | diary varbits | same |
| Personal best kill times | RuneLite's own `personalbest` config (written by the built-in Chat Commands plugin) | same |
| PvP record — player kills (from loot you receive), deaths where a player was involved, Wilderness loot keys picked up, GE value of PvP loot | RuneLite loot/death/inventory events, counted client-side **since install** (approximate; the game has no lifetime PK counter) | with progress data, while the "Track PvP record" toggle is on (default on) |
| Account identity | `Client.getAccountHash()` — **only ever transmitted as a SHA-256 hash** | during link + sync |
| Bank **summary** — total GE value, stack count, and which names from the public [items-of-interest list](https://osrs-accountaudit.vercel.app/api/items-of-interest) are present | bank container, reduced locally; item names/quantities never leave the client | **only on the panel's Sync bank button, or while the bank is open if the separate opt-in toggle is enabled** |

Everything above is information the vanilla client already shows the player. The plugin
performs **no gameplay actions**: no clicks, no input automation, no menu interaction,
no combat assistance, no information about other players.

## What it transmits, and where

- Destination: the RuneAudit API (the URL is visible and editable in plugin
  settings — nothing is hidden).
- Transport: HTTPS, authenticated by a per-link token issued during the link flow.
  Tokens are revocable from the website; unlinking deletes synced data server-side.
- Payload: exactly the table above, as JSON deltas; unchanged data is not resent.
- The bank summary is transmitted **only** on an explicit Sync bank press, or when the
  "Auto-sync bank summary" toggle (default OFF) is enabled. The plugin never transmits the
  bank inventory: it prices the bank locally with RuneLite's ItemManager and checks it
  against the published items-of-interest list, then sends one number, two counts, and
  the matching names. Stored encrypted at rest server-side.

## Consent model

- Nothing is transmitted until the player completes the link flow: they generate a
  code on the website while signed in there, and paste it into plugin settings while
  logged into the character — proving control of both sides.
- Two independent consent toggles: progress data (on after linking) and the bank
  summary (off by default, separate opt-in; the panel button is a one-shot consent).
- Turning the plugin off, clearing the token, or unlinking on the website stops all
  transmission immediately.

## What it will never do

- Never asks for, reads, or transmits Jagex/RuneLite credentials, session tokens,
  or email addresses.
- Never automates gameplay or provides an in-game advantage — read-and-report only,
  the same category as WikiSync, Wise Old Man, and TempleOSRS.
- Never reads chat, friends lists, clan data, location/position streams, or anything
  about other players.
- Never transmits the raw account hash — only a salted SHA-256.
- The plugin is and will remain fully functional for free; the website's optional AI
  features are separate from and never gate any plugin functionality.

## Dependencies

None beyond the standard RuneLite client APIs (OkHttp and Gson are injected from the
client itself). No third-party dependencies — nothing to hash-verify.
