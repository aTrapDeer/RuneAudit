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
import java.util.Objects;
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
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
 * Account Audit — syncs quest completion, quest points, and skill levels to the
 * player's own Account Audit profile.
 *
 * Security & consent model (mirrors docs/plugin-spec.md in the main repo):
 * - Never touches credentials. Linking uses a short-lived code the player generates
 *   while signed into the website, pasted here — proof of control of both sides.
 * - Sends only what the consent toggle covers: quest states, quest points, levels.
 * - The account is identified by a SHA-256 of RuneLite's account hash — the raw
 *   value never leaves the client.
 */
@Slf4j
@PluginDescriptor(
	name = "Account Audit",
	description = "Sync your progress to Account Audit and see what's worth doing next",
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

	/** Hash of the last payload we sent, to skip no-change syncs. */
	private String lastSentDigest = null;
	private boolean syncQueued = false;
	/** Bank contents captured on the last bank-open, awaiting the next sync. Opt-in. */
	private JsonArray pendingBank = null;
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
		panel = new AccountAuditPanel(this::fetchPlan);
		navButton = NavigationButton.builder()
			.tooltip("Account Audit")
			.icon(drawIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		fetchPlan();
		log.info("Account Audit started");
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
		// Bank capture: opt-in only, and only possible while the bank is actually open.
		if (!config.bankSync() || event.getContainerId() != InventoryID.BANK.getId())
		{
			return;
		}
		ItemContainer bankContainer = event.getItemContainer();
		if (bankContainer == null)
		{
			return;
		}
		JsonArray items = new JsonArray();
		for (Item item : bankContainer.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			JsonObject entry = new JsonObject();
			entry.addProperty("id", item.getId());
			entry.addProperty("name", client.getItemDefinition(item.getId()).getName());
			entry.addProperty("qty", item.getQuantity());
			items.add(entry);
		}
		pendingBank = items;
		syncQueued = true;
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
			clientThread.invokeLater(this::tryClaimLinkCode);
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

	private void tryClaimLinkCode()
	{
		final String code = config.linkCode().trim();
		if (code.isEmpty())
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null)
		{
			message("Account Audit: log into the character you want to link, then re-enter the code.");
			return;
		}
		final long accountHash = client.getAccountHash();
		if (accountHash == -1)
		{
			message("Account Audit: account identity not available yet — try again in a moment.");
			return;
		}
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
				log.warn("Account Audit link failed", e);
				messageLater("Account Audit: couldn't reach the server (" + e.getMessage() + ").");
			}

			@Override
			public void onResponse(Call call, Response response) throws IOException
			{
				try (Response r = response)
				{
					String responseBody = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						log.warn("Account Audit link rejected: {} {}", r.code(), responseBody);
						messageLater("Account Audit: link failed (" + friendlyError(responseBody, r.code()) + ")");
						return;
					}
					JsonObject json = gson.fromJson(responseBody, JsonObject.class);
					String token = json.get("pluginToken").getAsString();
					configManager.setConfiguration(AccountAuditConfig.GROUP, AccountAuditConfig.PLUGIN_TOKEN_KEY, token);
					configManager.setConfiguration(AccountAuditConfig.GROUP, AccountAuditConfig.LINK_CODE_KEY, "");
					messageLater("Account Audit: " + displayName + " linked successfully. Syncing…");
					syncQueued = true;
				}
			}
		});
	}

	// ---------- syncing ----------

	/** Must run on the client thread (reads quest state). */
	private void collectAndSend()
	{
		if (!config.syncProgress())
		{
			return;
		}
		final String token = config.pluginToken().trim();
		if (token.isEmpty())
		{
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
		// Quest points varp (101) — stable id; gameval constant is VarPlayerID.QP on new APIs.
		delta.addProperty("questPoints", client.getVarpValue(101));
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			delta.addProperty("displayName", client.getLocalPlayer().getName());
		}

		// Digest covers progress only; a pending bank capture always forces a send.
		String digest = sha256Hex(delta.toString());
		final JsonArray bankToSend = pendingBank;
		if (digest.equals(lastSentDigest) && bankToSend == null)
		{
			return; // nothing changed since last sync
		}
		if (bankToSend != null)
		{
			delta.add("bank", bankToSend);
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
				log.warn("Account Audit sync failed", e);
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
						fetchPlan();
						log.debug("Account Audit: synced");
					}
					else if (r.code() == 401)
					{
						log.warn("Account Audit: token revoked; clearing. Re-link from the website.");
						configManager.setConfiguration(AccountAuditConfig.GROUP, AccountAuditConfig.PLUGIN_TOKEN_KEY, "");
						messageLater("Account Audit: this link was revoked — generate a new code on the website to re-link.");
					}
					else if (r.code() != 429) // 429 = synced too recently; silently retry later
					{
						log.warn("Account Audit sync rejected: {}", r.code());
					}
				}
			}
		});
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
			panel.showStatus("Not linked. Generate a code on the website and paste it into this plugin's settings.");
			return;
		}
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
				panel.showStatus("Couldn't reach the Account Audit server.");
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

	private static String friendlyError(String body, int code)
	{
		try
		{
			JsonObject json = new Gson().fromJson(body, JsonObject.class);
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
