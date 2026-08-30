package com.accountaudit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The 23 skill keys the ingest API accepts — must match core's SKILLS list. */
final class SkillKeys
{
	static final Set<String> KNOWN = new HashSet<>(Arrays.asList(
		"attack", "defence", "strength", "hitpoints", "ranged", "prayer", "magic",
		"cooking", "woodcutting", "fletching", "fishing", "firemaking", "crafting",
		"smithing", "mining", "herblore", "agility", "thieving", "slayer", "farming",
		"runecraft", "hunter", "construction"
	));

	private SkillKeys()
	{
	}
}
