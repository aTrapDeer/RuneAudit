package com.accountaudit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.google.inject.Provides;

/**
 * RuneAudit — syncs quest completion, quest points, and skill levels to the
 * player's own RuneAudit profile.
 *
 * Security & consent model (mirrors docs/plugin-spec.md in the main repo):
 * - Never touches credentials. Linking uses a short-lived code the player generates
 *   while signed into the website, pasted here — proof of control of both sides.
 * - Sends only what the consent toggles cover: quest states, quest points, levels,
 *   worn gear, diaries, personal bests — and, separately opted in, a bank SUMMARY
 *   (total value + which published items-of-interest are present), never the bank.
 * - The account is identified by a SHA-256 of RuneLite's account hash — the raw
 *   value never leaves the client.
 */
@Slf4j
@PluginDescriptor(
	name = "RuneAudit",
	description = "Sync your progress to RuneAudit and see what's worth doing next",
	tags = {"quests", "progress", "plans", "sync"}
)
public class AccountAuditPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private AccountAuditConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	/** Hash of the last payload we sent, to skip no-change syncs. */
	private String lastSentDigest = null;
	private boolean syncQueued = false;
	/**
	 * Bank SUMMARY captured on the last bank-open, awaiting the next sync. Opt-in.
	 * Holds total value, stack counts, and which items-of-interest are present —
	 * never the inventory itself (see SCOPE.md).
	 */
	private JsonObject pendingBank = null;
	/** Lower-cased names from GET /api/items-of-interest — the only ownership vocabulary. */
	private volatile Set<String> interestNames = null;
	private AccountAuditPanel panel;
	private NavigationButton navButton;

	@Provides
	AccountAuditConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AccountAuditConfig.class);
	}

	@Override
	protected void startUp()
	{
		panel = new AccountAuditPanel(
			this::fetchPlan,
			code -> clientThread.invokeLater(() -> tryClaimLinkCode(code)),
			() -> clientThread.invokeLater(() ->
			{
				if (client.getGameState() != GameState.LOGGED_IN)
				{
					panel.showStatus("Log into the game first, then press Sync now.");
					return;
				}
				panel.showStatus("Syncing…");
				syncQueued = false;
				collectAndSend(true);
			}),
			() -> clientThread.invokeLater(this::syncBankNow));
		navButton = NavigationButton.builder()
			.tooltip("RuneAudit")
			.icon(drawIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		fetchItemsOfInterest();
		fetchPlan();
		log.info("RuneAudit started");
	}

	@Override
	protected void shutDown()
	{
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		lastSentDigest = null;
		pendingBank = null;
	}

	/** Programmatic icon — keeps the repo free of binary assets. */
	private static BufferedImage drawIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(new Color(0x9C6D0F));
		g.fillRoundRect(0, 0, 16, 16, 5, 5);
		g.setColor(new Color(0xFDF6E0));
		g.setFont(new Font(Font.SERIF, Font.BOLD, 12));
		g.drawString("A", 4, 12);
		g.dispose();
		return img;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			// Quest states settle a few ticks after login; the scheduled sync picks it up.
			syncQueued = true;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// Automatic bank-summary capture: opt-in toggle, fires while the bank is open.
		if (!config.bankSync() || event.getContainerId() != InventoryID.BANK.getId())
		{
			return;
		}
		if (captureBank(event.getItemContainer()))
		{
			syncQueued = true;
		}
	}

	/**
	 * Reduce a bank container to the summary we are willing to transmit:
	 * total GE value (priced locally by RuneLite's ItemManager, coins/platinum at face
	 * value), stack counts, and which published items-of-interest are present. Item
	 * names and quantities never leave this method. Returns false when unavailable.
	 */
	private boolean captureBank(ItemContainer bankContainer)
	{
		if (bankContainer == null)
		{
			return false;
		}
		Set<String> interest = interestNames;
		if (interest == null)
		{
			// Without the published list we can't say what's owned — fetch and wait.
			fetchItemsOfInterest();
			return false;
		}
		long valueGp = 0;
		int itemCount = 0;
		int unpriced = 0;
		Set<String> owned = new HashSet<>();
		for (Item item : bankContainer.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			itemCount++;
			int unit = itemManager.getItemPrice(item.getId());
			if (unit > 0)
			{
				valueGp += (long) unit * item.getQuantity();
			}
			else
			{
				unpriced++;
			}
			String name = client.getItemDefinition(item.getId()).getName().toLowerCase(Locale.ROOT);
			for (String tracked : interest)
			{
				// "toxic blowpipe (empty)" owns "toxic blowpipe"; "bow of faerdhinen (c)" owns
				// "bow of faerdhinen". Only bank-name-contains-tracked-name, never the reverse,
				// so a plain "Cape" can't claim a fire cape.
				if (name.contains(stripVariant(tracked)))
				{
					owned.add(tracked);
				}
			}
		}
		JsonObject summary = new JsonObject();
		summary.addProperty("valueGp", valueGp);
		summary.addProperty("itemCount", itemCount);
		summary.addProperty("unpricedCount", unpriced);
		JsonArray ownedArr = new JsonArray();
		for (String tracked : owned)
		{
			ownedArr.add(tracked);
		}
		summary.add("owned", ownedArr);
		pendingBank = summary;
		return true;
	}

	private static String stripVariant(String trackedName)
	{
		return trackedName.replace(" (c)", "").replace(" (f)", "");
	}

	/**
	 * The published vocabulary of ownership flags. Fetched once per session; anyone can
	 * open the URL to see exactly which item names the plugin may ever report.
	 */
	private void fetchItemsOfInterest()
	{
		Request request = new Request.Builder()
			.url(config.apiBase() + "/api/items-of-interest")
			.get()
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("RuneAudit: items-of-interest fetch failed", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						return;
					}
					JsonObject json = gson.fromJson(r.body().string(), JsonObject.class);
					if (!json.has("names"))
					{
						return;
					}
					Set<String> names = new HashSet<>();
					for (JsonElement el : json.getAsJsonArray("names"))
					{
						names.add(el.getAsString().toLowerCase(Locale.ROOT));
					}
					interestNames = names;
				}
			}
		});
	}

	/**
	 * The panel's Sync bank button — pressing it is explicit consent for this send.
	 * The client keeps the bank container in memory after one visit per session, so
	 * this works from anywhere once the bank has been opened.
	 */
	private void syncBankNow()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			panel.showStatus("Log into the game first, then press Sync bank.");
			return;
		}
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			panel.showStatus("Open your bank once this session, close it if you like, then press Sync bank again.");
			return;
		}
		if (!captureBank(bank))
		{
			panel.showStatus(interestNames == null
				? "Fetching the tracked-item list from the server — try again in a moment."
				: "Couldn't read the bank — open it and try again.");
			return;
		}
		panel.showStatus("Sending bank summary (value + " + pendingBank.getAsJsonArray("owned").size()
			+ " tracked items, no inventory)…");
		collectAndSend(true);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!AccountAuditConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if (AccountAuditConfig.LINK_CODE_KEY.equals(event.getKey())
			&& event.getNewValue() != null && !event.getNewValue().trim().isEmpty())
		{
			final String code = event.getNewValue();
			clientThread.invokeLater(() -> tryClaimLinkCode(code));
		}
	}

	/** Runs every 30s; actually syncs on login and every ~5 minutes, or when queued. */
	@Schedule(period = 30, unit = ChronoUnit.SECONDS)
	public void scheduledSync()
	{
		if (!syncQueued)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				syncQueued = false;
				collectAndSend();
			}
		});
	}

	@Schedule(period = 5, unit = ChronoUnit.MINUTES)
	public void periodicSync()
	{
		syncQueued = true;
	}

	// ---------- linking ----------

	private void tryClaimLinkCode(String rawCode)
	{
		final String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
		if (code.isEmpty())
		{
			panel.showStatus("Paste the code from the website first, then press Link.");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			panel.showStatus("Log into the character you want to link, then press Link again.");
			message("RuneAudit: log into the character you want to link, then try again.");
			return;
		}
		final long accountHash = client.getAccountHash();
		if (accountHash == -1)
		{
			panel.showStatus("Account identity not ready — wait a moment and press Link again.");
			return;
		}
		panel.showStatus("Linking…");
		final String displayName = Objects.requireNonNull(client.getLocalPlayer().getName());

		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		body.addProperty("accountHash", sha256Hex(Long.toString(accountHash)));
		body.addProperty("displayName", displayName);

		Request request = new Request.Builder()
			.url(config.apiBase() + "/api/link")
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("RuneAudit link failed", e);
				panel.showStatus("Couldn't reach the server (" + e.getMessage() + "). Check your connection and press Link again.");
				messageLater("RuneAudit: couldn't reach the server (" + e.getMessage() + ").");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					String responseBody = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						log.warn("RuneAudit link rejected: {} {}", r.code(), responseBody);
						String reason = friendlyError(responseBody, r.code());
						panel.showStatus("Link failed: " + reason);
						messageLater("RuneAudit: link failed (" + reason + ")");
						return;
					}
					JsonObject json = gson.fromJson(responseBody, JsonObject.class);
					String token = json.get("pluginToken").getAsString();
					configManager.setConfiguration(AccountAuditConfig.GROUP, AccountAuditConfig.PLUGIN_TOKEN_KEY, token);
					configManager.setConfiguration(AccountAuditConfig.GROUP, AccountAuditConfig.LINK_CODE_KEY, "");
					panel.setLinked(true);
					panel.showStatus("Linked as " + displayName + " ✓ — syncing…");
					messageLater("RuneAudit: " + displayName + " linked successfully. Syncing…");
					clientThread.invokeLater(() -> collectAndSend(true));
				}
			}
		});
	}

	// ---------- syncing ----------

	private void collectAndSend()
	{
		collectAndSend(false);
	}

	/** Must run on the client thread (reads quest state). force = ignore no-change skip. */
	private void collectAndSend(boolean force)
	{
		if (!config.syncProgress())
		{
			if (force)
			{
				panel.showStatus("Progress sync is disabled in the plugin settings.");
			}
			return;
		}
		final String token = config.pluginToken().trim();
		if (token.isEmpty())
		{
			if (force)
			{
				panel.showStatus("Not linked yet — paste a code from the website above and press Link.");
			}
			return;
		}

		JsonObject quests = new JsonObject();
		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			quests.addProperty(kebab(quest.getName()), mapState(state));
		}

		JsonObject levels = new JsonObject();
		for (Skill skill : Skill.values())
		{
			String key = skill.name().toLowerCase(Locale.ROOT);
			if (SkillKeys.KNOWN.contains(key))
			{
				levels.addProperty(key, client.getRealSkillLevel(skill));
			}
		}

		// Worn equipment only — visible to anyone in-game, so it sits in the progress
		// consent category. Bank is NOT read; that's a separate opt-in in a later phase.
		JsonArray equipment = new JsonArray();
		ItemContainer worn = client.getItemContainer(InventoryID.EQUIPMENT);
		if (worn != null)
		{
			for (Item item : worn.getItems())
			{
				if (item.getId() <= 0)
				{
					continue;
				}
				JsonObject entry = new JsonObject();
				entry.addProperty("id", item.getId());
				entry.addProperty("name", client.getItemDefinition(item.getId()).getName());
				entry.addProperty("qty", item.getQuantity());
				equipment.add(entry);
			}
		}

		JsonObject delta = new JsonObject();
		delta.add("quests", quests);
		delta.add("levels", levels);
		delta.add("equipment", equipment);
		delta.add("diaries", collectDiaries());
		delta.add("personalBests", collectPersonalBests());
		// Quest points varp (101) — stable id; gameval constant is VarPlayerID.QP on new APIs.
		delta.addProperty("questPoints", client.getVarpValue(101));
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			delta.addProperty("displayName", client.getLocalPlayer().getName());
		}

		// Digest covers progress only; a pending bank summary always forces a send.
		String digest = sha256Hex(delta.toString());
		final JsonObject bankToSend = pendingBank;
		if (!force && digest.equals(lastSentDigest) && bankToSend == null)
		{
			return; // nothing changed since last sync
		}
		if (bankToSend != null)
		{
			delta.add("bankSummary", bankToSend);
		}

		JsonObject payload = new JsonObject();
		payload.addProperty("schemaVersion", 1);
		payload.addProperty("capturedAt", Instant.now().toString());
		payload.add("delta", delta);

		Request request = new Request.Builder()
			.url(config.apiBase() + "/api/ingest")
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(payload)))
			.build();

		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				panel.showStatus("Sync failed: couldn't reach the server. Will retry automatically.");
				log.warn("RuneAudit sync failed", e);
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						lastSentDigest = digest;
						if (bankToSend != null && bankToSend == pendingBank)
						{
							pendingBank = null;
						}
						panel.showStatus("Synced ✓ — loading your plan…");
						fetchPlan();
						log.debug("RuneAudit: synced");
					}
					else if (r.code() == 401)
					{
						log.warn("RuneAudit: token revoked; clearing. Re-link from the website.");
						configManager.setConfiguration(AccountAuditConfig.GROUP, AccountAuditConfig.PLUGIN_TOKEN_KEY, "");
						panel.setLinked(false);
						panel.showStatus("This link was revoked on the website — generate a new code and re-link.");
						messageLater("RuneAudit: this link was revoked — generate a new code on the website to re-link.");
					}
					else if (r.code() == 429)
					{
						if (force)
						{
							panel.showStatus("Synced very recently — wait ~30 seconds and try again.");
						}
					}
					else
					{
						panel.showStatus("Sync failed (HTTP " + r.code() + ") — will retry automatically.");
						log.warn("RuneAudit sync rejected: {}", r.code());
					}
				}
			}
		});
	}

	/** Achievement diary TIER completion (per-task capture is a later, bigger job). */
	private JsonObject collectDiaries()
	{
		JsonObject diaries = new JsonObject();
		int[][] varbits = {
			{Varbits.DIARY_ARDOUGNE_EASY, Varbits.DIARY_ARDOUGNE_MEDIUM, Varbits.DIARY_ARDOUGNE_HARD, Varbits.DIARY_ARDOUGNE_ELITE},
			{Varbits.DIARY_DESERT_EASY, Varbits.DIARY_DESERT_MEDIUM, Varbits.DIARY_DESERT_HARD, Varbits.DIARY_DESERT_ELITE},
			{Varbits.DIARY_FALADOR_EASY, Varbits.DIARY_FALADOR_MEDIUM, Varbits.DIARY_FALADOR_HARD, Varbits.DIARY_FALADOR_ELITE},
			{Varbits.DIARY_FREMENNIK_EASY, Varbits.DIARY_FREMENNIK_MEDIUM, Varbits.DIARY_FREMENNIK_HARD, Varbits.DIARY_FREMENNIK_ELITE},
			{Varbits.DIARY_KANDARIN_EASY, Varbits.DIARY_KANDARIN_MEDIUM, Varbits.DIARY_KANDARIN_HARD, Varbits.DIARY_KANDARIN_ELITE},
			{Varbits.DIARY_KARAMJA_EASY, Varbits.DIARY_KARAMJA_MEDIUM, Varbits.DIARY_KARAMJA_HARD, Varbits.DIARY_KARAMJA_ELITE},
			{Varbits.DIARY_KOUREND_EASY, Varbits.DIARY_KOUREND_MEDIUM, Varbits.DIARY_KOUREND_HARD, Varbits.DIARY_KOUREND_ELITE},
			{Varbits.DIARY_LUMBRIDGE_EASY, Varbits.DIARY_LUMBRIDGE_MEDIUM, Varbits.DIARY_LUMBRIDGE_HARD, Varbits.DIARY_LUMBRIDGE_ELITE},
			{Varbits.DIARY_MORYTANIA_EASY, Varbits.DIARY_MORYTANIA_MEDIUM, Varbits.DIARY_MORYTANIA_HARD, Varbits.DIARY_MORYTANIA_ELITE},
			{Varbits.DIARY_VARROCK_EASY, Varbits.DIARY_VARROCK_MEDIUM, Varbits.DIARY_VARROCK_HARD, Varbits.DIARY_VARROCK_ELITE},
			{Varbits.DIARY_WESTERN_EASY, Varbits.DIARY_WESTERN_MEDIUM, Varbits.DIARY_WESTERN_HARD, Varbits.DIARY_WESTERN_ELITE},
			{Varbits.DIARY_WILDERNESS_EASY, Varbits.DIARY_WILDERNESS_MEDIUM, Varbits.DIARY_WILDERNESS_HARD, Varbits.DIARY_WILDERNESS_ELITE},
		};
		String[] regions = {"ardougne", "desert", "falador", "fremennik", "kandarin", "karamja", "kourend", "lumbridge", "morytania", "varrock", "western", "wilderness"};
		String[] tiers = {"easy", "medium", "hard", "elite"};
		for (int r = 0; r < regions.length; r++)
		{
			for (int t = 0; t < tiers.length; t++)
			{
				diaries.addProperty(regions[r] + "-" + tiers[t], client.getVarbitValue(varbits[r][t]) == 1);
			}
		}
		return diaries;
	}

	/**
	 * Personal bests recorded by RuneLite's own chat-commands plugin (kill times in
	 * seconds, stored per RS profile). Read-only reuse of data the player already has;
	 * raid points/team sizes aren't stored client-side, so those stay manual for now.
	 */
	private JsonObject collectPersonalBests()
	{
		String[] bosses = {
			"chambers of xeric", "chambers of xeric challenge mode",
			"theatre of blood", "theatre of blood hard mode",
			"tombs of amascut", "tombs of amascut expert mode",
			"zulrah", "vorkath", "grotesque guardians", "alchemical hydra",
			"gauntlet", "corrupted gauntlet", "fight cave", "inferno",
			"phantom muspah", "nightmare", "phosani's nightmare",
			"hallowed sepulchre", "duke sucellus", "the leviathan",
			"the whisperer", "vardorvis", "fragment of seren", "sol heredit",
		};
		JsonObject pbs = new JsonObject();
		for (String boss : bosses)
		{
			try
			{
				Double pb = configManager.getRSProfileConfiguration("personalbest", boss, double.class);
				if (pb != null && pb > 0)
				{
					pbs.addProperty(boss, pb);
				}
			}
			catch (RuntimeException ignored)
			{
				// unparseable legacy value — skip
			}
		}
		return pbs;
	}

	// ---------- side panel ----------

	private void fetchPlan()
	{
		final String token = config.pluginToken().trim();
		if (panel == null)
		{
			return;
		}
		if (token.isEmpty())
		{
			panel.setLinked(false);
			panel.showStatus("Not linked. Generate a code on the website, paste it above, and press Link.");
			return;
		}
		panel.setLinked(true);
		Request request = new Request.Builder()
			.url(config.apiBase() + "/api/plan")
			.header("Authorization", "Bearer " + token)
			.get()
			.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				panel.showStatus("Couldn't reach the RuneAudit server.");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						panel.showStatus("Plan unavailable (HTTP " + r.code() + "). Re-link if this persists.");
						return;
					}
					JsonObject json = gson.fromJson(r.body().string(), JsonObject.class);
					List<String> steps = new ArrayList<>();
					if (json.has("steps"))
					{
						for (JsonElement el : json.getAsJsonArray("steps"))
						{
							JsonObject step = el.getAsJsonObject();
							steps.add(step.get("label").getAsString());
						}
					}
					String planLine = json.has("done")
						? "Quest route: " + json.get("done").getAsInt() + "/" + json.get("total").getAsInt() + " done"
						: "Synced.";
					String suggestionName = null;
					String suggestionWhy = null;
					if (json.has("suggestion") && json.get("suggestion").isJsonObject())
					{
						JsonObject s = json.getAsJsonObject("suggestion");
						suggestionName = s.get("name").getAsString();
						suggestionWhy = s.get("why").getAsString();
					}
					panel.showPlan(planLine, steps, suggestionName, suggestionWhy);
				}
			}
		});
	}

	// ---------- helpers ----------

	private static String mapState(QuestState state)
	{
		switch (state)
		{
			case FINISHED:
				return "complete";
			case IN_PROGRESS:
				return "started";
			default:
				return "incomplete";
		}
	}

	/** "Dragon Slayer I" -> "dragon-slayer-i"; matches the web app's quest ids.
	 *  "&" expands to "and" so "Romeo & Juliet" -> "romeo-and-juliet". */
	static String kebab(String name)
	{
		return name.toLowerCase(Locale.ROOT)
			.replace("'", "")
			.replace("&", " and ")
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-)|(-$)", "");
	}

	private static String sha256Hex(String input)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(hash.length * 2);
			for (byte b : hash)
			{
				sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
			}
			return sb.toString();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	private String friendlyError(String body, int code)
	{
		try
		{
			JsonObject json = gson.fromJson(body, JsonObject.class);
			if (json != null && json.has("message"))
			{
				return json.get("message").getAsString();
			}
		}
		catch (Exception ignored)
		{
		}
		return "HTTP " + code;
	}

	private void message(String text)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", text, null);
	}

	private void messageLater(String text)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				message(text);
			}
		});
	}
}
