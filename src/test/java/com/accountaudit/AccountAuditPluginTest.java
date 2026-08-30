package com.accountaudit;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches a full RuneLite client with the Account Audit plugin loaded — the standard
 * way plugin developers test against their own account before Plugin Hub publication.
 *
 *   gradlew.bat runClient      (or run this main() from an IDE)
 *
 * You log in through the normal RuneLite/Jagex login flow; the plugin never sees
 * credentials in either case.
 */
public class AccountAuditPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(AccountAuditPlugin.class);
		RuneLite.main(args);
	}
}
