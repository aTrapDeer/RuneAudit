package com.accountaudit;

import java.awt.BorderLayout;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/**
 * The in-client panel: link with an explicit button, sync with an explicit button,
 * always-visible status. All strategy logic lives server-side.
 */
public class AccountAuditPanel extends PluginPanel
{
	private final JLabel status = new JLabel();
	private final JTextField codeField = new JTextField();
	private final JButton linkButton = new JButton("Link");
	private final JButton syncButton = new JButton("Sync now");
	private final JButton bankButton = new JButton("Sync bank");
	private final JPanel linkRow = new JPanel();
	private final JPanel stepsPanel = new JPanel();
	private final JLabel suggestionTitle = new JLabel();
	private final JLabel suggestion = new JLabel();

	AccountAuditPanel(Runnable onRefresh, Consumer<String> onLink, Runnable onSyncNow, Runnable onSyncBank)
	{
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("Account Audit");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		content.add(title);
		content.add(Box.createVerticalStrut(6));
		content.add(status);
		content.add(Box.createVerticalStrut(8));

		// Link row: paste the website code, press Link, watch the status line.
		linkRow.setLayout(new BoxLayout(linkRow, BoxLayout.Y_AXIS));
		JLabel linkLabel = new JLabel("Link code from the website:");
		linkRow.add(linkLabel);
		codeField.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 28));
		codeField.setToolTipText("Generate this on the website's My Accounts page while signed in");
		linkRow.add(codeField);
		linkRow.add(Box.createVerticalStrut(4));
		linkButton.addActionListener(e -> onLink.accept(codeField.getText()));
		linkRow.add(linkButton);
		content.add(linkRow);
		content.add(Box.createVerticalStrut(10));

		stepsPanel.setLayout(new BoxLayout(stepsPanel, BoxLayout.Y_AXIS));
		content.add(stepsPanel);
		content.add(Box.createVerticalStrut(10));

		suggestionTitle.setFont(suggestionTitle.getFont().deriveFont(Font.BOLD));
		content.add(suggestionTitle);
		content.add(suggestion);
		content.add(Box.createVerticalStrut(10));

		syncButton.addActionListener(e -> onSyncNow.run());
		content.add(syncButton);
		content.add(Box.createVerticalStrut(4));

		bankButton.setToolTipText("Sends your bank contents to YOUR private profile (encrypted) so the site can value it and suggest gear upgrades. Open your bank once this session first.");
		bankButton.addActionListener(e -> onSyncBank.run());
		content.add(bankButton);
		content.add(Box.createVerticalStrut(4));

		JButton refresh = new JButton("Refresh plan");
		refresh.addActionListener(e -> onRefresh.run());
		content.add(refresh);

		add(content, BorderLayout.NORTH);
		showStatus("Checking link…");
	}

	void showStatus(String text)
	{
		SwingUtilities.invokeLater(() -> status.setText(asHtml(text)));
	}

	/** Toggle the link controls: hidden once linked, shown when not. */
	void setLinked(boolean linked)
	{
		SwingUtilities.invokeLater(() ->
		{
			linkRow.setVisible(!linked);
			syncButton.setVisible(linked);
			bankButton.setVisible(linked);
			if (linked)
			{
				codeField.setText("");
			}
			revalidate();
			repaint();
		});
	}

	void clearCodeField()
	{
		SwingUtilities.invokeLater(() -> codeField.setText(""));
	}

	void showPlan(String planLine, java.util.List<String> steps, String suggestionName, String suggestionWhy)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(asHtml(planLine));
			stepsPanel.removeAll();
			for (String step : steps)
			{
				stepsPanel.add(new JLabel(asHtml("• " + step)));
			}
			if (suggestionName != null)
			{
				suggestionTitle.setText("Worth a look:");
				suggestion.setText(asHtml(suggestionName + " — " + suggestionWhy));
			}
			else
			{
				suggestionTitle.setText("");
				suggestion.setText("");
			}
			stepsPanel.revalidate();
			stepsPanel.repaint();
		});
	}

	private static String asHtml(String text)
	{
		return "<html><body style='width: 180px'>" + text
			.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
	}
}
