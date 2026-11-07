package software.coley.recaf.ui.pane.mcp;

import jakarta.annotation.Nonnull;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.carbonicons.CarbonIcons;
import software.coley.recaf.mcp.McpActivity;
import software.coley.recaf.mcp.McpAssistant;
import software.coley.recaf.ui.control.FontIconView;
import software.coley.recaf.util.ClipboardUtil;

/**
 * Live view of what an AI agent is doing inside Recaf.
 * <p>
 * Shows the connection details, the class the agent is currently looking at, a feed of every
 * tool call, and the source or diff that goes with the selected entry.
 *
 * @author Recaf AI-MCP
 */
public class McpActivityPane extends BorderPane {

	public McpActivityPane(@Nonnull McpAssistant assistant) {
		// Activity feed.
		ListView<McpActivity> list = new ListView<>();
		list.setItems(assistant.getActivities());
		list.setCellFactory(view -> new ActivityCell());
		list.setPlaceholder(new Label("No AI activity yet. Connect a client and ask it to inspect a class."));

		TextArea detail = new TextArea();
		detail.setEditable(false);
		detail.setWrapText(false);
		detail.setStyle("-fx-font-family: 'Consolas', 'JetBrains Mono', monospace; -fx-font-size: 12px;");
		detail.setPromptText("Select an entry to inspect the source or diff the AI saw.");

		list.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, newValue) -> {
			if (newValue == null)
				detail.clear();
			else
				detail.setText(newValue.summary() + "\n\n" + newValue.detail());
		});

		// Keep the newest entry visible, and jump to edits so the diff is on screen.
		assistant.getActivities().addListener((ListChangeListener<McpActivity>) change -> {
			while (change.next()) {
				if (!change.wasAdded() || change.getAddedSize() == 0)
					continue;
				int index = change.getTo() - 1;
				if (index < 0 || index >= assistant.getActivities().size())
					continue;
				McpActivity activity = assistant.getActivities().get(index);
				list.scrollTo(index);
				if (McpActivity.KIND_EDIT.equals(activity.kind()))
					list.getSelectionModel().select(index);
			}
		});

		// Header.
		Label dot = new Label("\u25CF");
		dot.styleProperty().bind(Bindings.when(assistant.runningProperty())
				.then("-fx-text-fill: #4caf50;")
				.otherwise("-fx-text-fill: #9e9e9e;"));

		Label title = new Label("MCP");
		title.setStyle("-fx-font-weight: bold;");

		Label endpoint = new Label();
		endpoint.textProperty().bind(assistant.endpointProperty());
		endpoint.setStyle("-fx-font-family: 'Consolas', monospace;");
		endpoint.setTooltip(new Tooltip("HTTP endpoint for AI clients"));

		Button toggle = new Button();
		toggle.textProperty().bind(Bindings.when(assistant.runningProperty())
				.then("Stop")
				.otherwise("Start"));
		toggle.setOnAction(e -> {
			if (assistant.runningProperty().get())
				assistant.stopServer();
			else
				assistant.startServer();
		});

		Button copy = new Button("Copy client config");
		copy.setGraphic(new FontIconView(CarbonIcons.COPY));
		copy.setTooltip(new Tooltip("Copy a ready to paste MCP client configuration"));
		copy.setOnAction(e -> ClipboardUtil.copyString(assistant.clientConfig()));

		Button clear = new Button("Clear");
		clear.setGraphic(new FontIconView(CarbonIcons.TRASH_CAN));
		clear.setOnAction(e -> assistant.getActivities().clear());

		Button openInEditor = new Button("Open in editor");
		openInEditor.setGraphic(new FontIconView(CarbonIcons.CODE));
		openInEditor.setOnAction(e -> {
			McpActivity activity = list.getSelectionModel().getSelectedItem();
			if (activity != null && !activity.target().isEmpty())
				assistant.openClass(activity.target());
		});

		Region spacer = new Region();
		HBox.setHgrow(spacer, Priority.ALWAYS);

		HBox toolbar = new HBox(6, dot, title, endpoint, spacer, openInEditor, toggle, clear, copy);
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.setPadding(new Insets(8));
		toolbar.getStyleClass().add("padded");

		Label viewing = new Label();
		viewing.textProperty().bind(Bindings.concat("AI is viewing: ", assistant.lastViewedProperty()));
		viewing.setStyle("-fx-font-weight: bold;");
		viewing.setWrapText(true);

		Label status = new Label();
		status.textProperty().bind(assistant.statusMessageProperty());
		status.setStyle("-fx-opacity: 0.75;");
		status.setWrapText(true);

		VBox statusBox = new VBox(2, viewing, status);
		statusBox.setPadding(new Insets(0, 8, 8, 8));

		// Layout.
		SplitPane split = new SplitPane(list, detail);
		split.setOrientation(Orientation.VERTICAL);
		split.setDividerPositions(0.55);
		VBox.setVgrow(split, Priority.ALWAYS);

		VBox root = new VBox(toolbar, statusBox, split);
		root.setFillWidth(true);
		setCenter(root);
	}

	/**
	 * Cell rendering a single activity entry.
	 */
	private static class ActivityCell extends ListCell<McpActivity> {
		@Override
		protected void updateItem(McpActivity activity, boolean empty) {
			super.updateItem(activity, empty);
			if (empty || activity == null) {
				setText(null);
				setGraphic(null);
				return;
			}

			Color color = switch (activity.kind()) {
				case McpActivity.KIND_EDIT -> Color.ORANGE;
				case McpActivity.KIND_VIEW -> Color.STEELBLUE;
				default -> Color.GRAY;
			};
			if (activity.error())
				color = Color.RED;

			setGraphic(new FontIconView(switch (activity.kind()) {
				case McpActivity.KIND_EDIT -> CarbonIcons.EDIT;
				case McpActivity.KIND_VIEW -> CarbonIcons.VIEW;
				default -> CarbonIcons.SEARCH;
			}, 14, color));
			setText(activity.timeText() + "  " + activity.summary());
		}
	}
}
