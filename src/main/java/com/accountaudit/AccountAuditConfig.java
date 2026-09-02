package com.accountaudit;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(AccountAuditConfig.GROUP)
public interface AccountAuditConfig extends Config
{
	String GROUP = "accountaudit";
	String LINK_CODE_KEY = "linkCode";
	String PLUGIN_TOKEN_KEY = "pluginToken";

	@ConfigSection(
		name = "Linking",
		description = "Connect this character to your RuneAudit profile",
		position = 0
	)
	String linkingSection = "linking";

	@ConfigItem(
		keyName = LINK_CODE_KEY,
		name = "Link code",
		description = "Paste the code from the RuneAudit website (My accounts page) while logged into the character you want to link. It is consumed on use.",
		position = 1,
		section = linkingSection
	)
	default String linkCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = PLUGIN_TOKEN_KEY,
		name = "Plugin token",
		description = "Issued automatically when a link code is claimed. Clear it to stop syncing; unlink on the website to revoke it entirely.",
		position = 2,
		secret = true,
		section = linkingSection
	)
	default String pluginToken()
	{
		return "";
	}

	@ConfigSection(
		name = "What is synced",
		description = "Consent toggles — nothing is sent unless a category is enabled",
		position = 1
	)
	String consentSection = "consent";

	@ConfigItem(
		keyName = "syncProgress",
		name = "Sync progress data",
		description = "Sends quest completion, quest points, skill levels, and worn equipment to your private RuneAudit profile. No chat, no location.",
		position = 3,
		section = consentSection
	)
	default boolean syncProgress()
	{
		return true;
	}

	@ConfigItem(
		keyName = "bankSync",
		name = "Sync bank contents (opt-in)",
		description = "OFF by default. When enabled, your bank is captured whenever you open it and stored ENCRYPTED on the server, visible only to you. Powers bank value and gear-aware recommendations.",
		position = 4,
		section = consentSection
	)
	default boolean bankSync()
	{
		return false;
	}

	@ConfigSection(
		name = "Advanced",
		description = "Server settings",
		position = 2,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@ConfigItem(
		keyName = "apiBase",
		name = "API base URL",
		description = "Where to sync to. Leave default unless you run your own instance (dev: http://localhost:3000).",
		position = 4,
		section = advancedSection
	)
	default String apiBase()
	{
		return "https://osrs-accountaudit.vercel.app";
	}
}
