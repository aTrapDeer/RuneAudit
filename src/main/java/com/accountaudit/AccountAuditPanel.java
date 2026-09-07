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
import net.runelite.client.util.LinkBrowser;

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
	private final JLabel ratingLine = new JLabel();
	private final JLabel ratingNote = new JLabel();
	private final JButton ratingButton = new JButton("Refresh rating");
	private Runnable onRefreshRating = () -> {};
	private final JLabel suggestionTitle = new JLabel();
	private final JPanel picksPanel = new JPanel();
	private final JButton openButton = new JButton("Open on RuneAudit");
	private String profileUrl = null;

	AccountAuditPanel(Runnable onRefresh, Consumer<String> onLink, Runnable onSyncNow, Runnable onSyncBank)
	{
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

		JLabel title = new JLabel("RuneAudit");
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		content.add(title);
		content.add(Box.createVerticalStrut(6));
		content.add(status);
		content.add(Box.createVerticalStrut(8));

		// Rating badge: computed on the site from synced data (no AI, no credits);
		// updates after each sync, and on demand at most once a day.
		ratingLine.setFont(ratingLine.getFont().deriveFont(Font.BOLD, 13f));
		content.add(ratingLine);
		content.add(ratingNote);
		ratingButton.setToolTipText("Recompute your account rating from the latest synced data. Once a day; it also updates automatically after each sync.");
		ratingButton.addActionListener(e -> onRefreshRating.run());
		ratingButton.setVisible(false);
		content.add(ratingButton);
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
		picksPanel.setLayout(new BoxLayout(picksPanel, BoxLayout.Y_AXIS));
		content.add(picksPanel);
		content.add(Box.createVerticalStrut(10));

		openButton.setToolTipText("Open this character's RuneAudit page in your browser");
		openButton.addActionListener(e ->
		{
			if (profileUrl != null)
			{
				LinkBrowser.browse(profileUrl);
			}
		});
		openButton.setVisible(false);
		content.add(openButton);
		content.add(Box.createVerticalStrut(4));

		syncButton.addActionListener(e -> onSyncNow.run());
		content.add(syncButton);
		content.add(Box.createVerticalStrut(4));

		bankButton.setToolTipText("Sends a bank SUMMARY to your private profile: total value and which tracked gear items you have. Never the bank itself. Open your bank once this session first.");
		bankButton.addActionListener(e -> onSyncBank.run());
		content.add(bankButton);
		content.add(Box.createVerticalStrut(4));

		JButton refresh = new JButton("Refresh plan");
		refresh.addActionListener(e -> onRefresh.run());
		content.add(refresh);

		add(content, BorderLayout.NORTH);
		showStatus("Checking link…");
	}

	void setOnRefreshRating(Runnable r)
	{
		onRefreshRating = r;
	}

	/** Tier badge line, e.g. "Rating: B 68 · Rune Planner (late)" plus the top note. */
	void showRating(String letter, int score, String tier, String stage, boolean complete, String note)
	{
		SwingUtilities.invokeLater(() ->
		{
			ratingLine.setText(asHtml("Rating: " + letter + " " + score + " · " + tier + " (" + stage + ")" + (complete ? "" : " · provisional")));
			ratingNote.setText(note == null ? "" : asHtml(note));
			ratingButton.setVisible(true);
			revalidate();
			repaint();
		});
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
			if (!linked)
			{
				ratingLine.setText("");
				ratingNote.setText("");
				ratingButton.setVisible(false);
			}
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

	/** picks: [name, why] pairs — the site's two solver picks with their "tailored because" line. */
	void showPlan(String planLine, java.util.List<String> steps, java.util.List<String[]> picks, String url)
	{
		SwingUtilities.invokeLater(() ->
		{
			status.setText(asHtml(planLine));
			stepsPanel.removeAll();
			for (String step : steps)
			{
				stepsPanel.add(new JLabel(asHtml("• " + step)));
			}
			picksPanel.removeAll();
			suggestionTitle.setText(picks.isEmpty() ? "" : (picks.size() > 1 ? "Two things worth doing:" : "Worth a look:"));
			for (String[] pick : picks)
			{
				JLabel name = new JLabel(asHtml(pick[0]));
				name.setFont(name.getFont().deriveFont(Font.BOLD));
				picksPanel.add(name);
				picksPanel.add(new JLabel(asHtml(pick[1])));
				picksPanel.add(Box.createVerticalStrut(6));
			}
			profileUrl = url;
			openButton.setVisible(url != null);
			revalidate();
			repaint();
		});
	}

	private static String asHtml(String text)
	{
		return "<html><body style='width: 165px'>" + text
			.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "</body></html>";
	}
}
