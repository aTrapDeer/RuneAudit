package com.accountaudit;

import java.awt.BorderLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/**
 * The in-client view of the active plan: next steps + one suggestion, served by
 * GET /api/plan. All strategy logic lives server-side, so knowledge-base updates
 * never require a plugin release.
 */
public class AccountAuditPanel extends PluginPanel
{
	private final JLabel status = new JLabel("Not linked yet.");
	private final JPanel stepsPanel = new JPanel();
	private final JLabel suggestionTitle = new JLabel();
	private final JLabel suggestion = new JLabel();

	AccountAuditPanel(Runnable onRefresh)
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
		content.add(Box.createVerticalStrut(10));

		stepsPanel.setLayout(new BoxLayout(stepsPanel, BoxLayout.Y_AXIS));
		content.add(stepsPanel);
		content.add(Box.createVerticalStrut(10));

		suggestionTitle.setFont(suggestionTitle.getFont().deriveFont(Font.BOLD));
		content.add(suggestionTitle);
		content.add(suggestion);
		content.add(Box.createVerticalStrut(10));

		JButton refresh = new JButton("Refresh plan");
		refresh.addActionListener(e -> onRefresh.run());
		content.add(refresh);

		add(content, BorderLayout.NORTH);
	}

	void showStatus(String text)
	{
		SwingUtilities.invokeLater(() -> status.setText(asHtml(text)));
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
