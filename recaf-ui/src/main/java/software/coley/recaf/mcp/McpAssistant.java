package software.coley.recaf.mcp;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.stage.Stage;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.compile.JavacCompilerConfig;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.NavigationManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.ui.pane.mcp.McpActivityPane;
import software.coley.recaf.ui.window.RecafScene;
import software.coley.recaf.ui.window.RecafStage;
import software.coley.recaf.util.FxThreadUtil;
import software.coley.recaf.workspace.WorkspaceSaveService;

import java.util.ArrayList;
import java.util.List;

/**
 * Lifecycle owner for the MCP bridge, and the shared activity feed shown in the UI.
 *
 * @author Recaf AI-MCP
 */
@ApplicationScoped
public class McpAssistant implements McpServer.Listener {
	private static final Logger logger = Logging.get(McpAssistant.class);
	private static final int MAX_ACTIVITY = 400;

	private final McpConfig config;
	private final McpPlatform platform;
	private final McpUiBridge ui;
	private final McpServer server;
	private final ObservableList<McpActivity> activities = FXCollections.observableArrayList();
	private final BooleanProperty running = new SimpleBooleanProperty(false);
	private final StringProperty endpoint = new SimpleStringProperty("");
	private final StringProperty statusMessage = new SimpleStringProperty("");
	private final StringProperty lastViewed = new SimpleStringProperty("");
	private Stage window;
	private boolean initialized;

	@Inject
	public McpAssistant(@Nonnull McpConfig config,
	                    @Nonnull WorkspaceManager workspaceManager,
	                    @Nonnull DecompilerManager decompilerManager,
	                    @Nonnull AssemblerPipelineManager pipelineManager,
	                    @Nonnull JavacCompiler javac,
	                    @Nonnull JavacCompilerConfig javacConfig,
	                    @Nonnull WorkspaceSaveService saveService,
	                    @Nonnull Actions actions,
	                    @Nonnull DockingManager dockingManager,
	                    @Nonnull NavigationManager navigationManager) {
		this.config = config;
		this.platform = new McpPlatform(config, workspaceManager, decompilerManager, pipelineManager,
				javac, javacConfig, saveService);
		this.ui = new McpUiBridge(platform, actions, dockingManager, navigationManager);
		this.server = new McpServer(config, platform, ui, this);
		this.endpoint.set(server.getEndpoint());
		this.statusMessage.set("Stopped");
	}

	// ================================================================== lifecycle

	/**
	 * Starts the server when configured to do so and reveals the activity window.
	 * <b>Must be called on the FX thread.</b>
	 */
	public void initialize() {
		if (initialized)
			return;
		initialized = true;

		if (config.getEnabled().getValue())
			startServer();
		else
			statusMessage.set("Disabled in config");

		config.getEnabled().addChangeListener((observable, oldValue, newValue) -> {
			if (Boolean.TRUE.equals(newValue))
				startServer();
			else
				stopServer();
		});
		config.getPort().addChangeListener((observable, oldValue, newValue) -> restartServer());

		if (config.getAutoShowWindow().getValue())
			showWindow();
	}

	/**
	 * Starts the MCP server. Any previous instance is stopped first.
	 */
	public void startServer() {
		McpFx.runAndWait(() -> {
			try {
				server.stop();
				server.start();
				running.set(true);
				endpoint.set(server.getEndpoint());
				int requested = config.getPort().getValue();
				if (server.getPort() != requested)
					statusMessage.set("Listening on port " + server.getPort() + " (port " + requested + " was busy)");
				else
					statusMessage.set("Listening");
			} catch (Throwable t) {
				logger.error("Failed to start the MCP server", t);
				running.set(false);
				statusMessage.set("Failed to bind port " + config.getPort().getValue() + ": " + t.getMessage());
				server.reportStartFailure(statusMessage.get());
			}
		});
	}

	/**
	 * Stops the MCP server.
	 */
	public void stopServer() {
		McpFx.runAndWait(() -> {
			server.stop();
			running.set(false);
			statusMessage.set("Stopped");
		});
	}

	/**
	 * Restarts the server, used when the port changes.
	 */
	public void restartServer() {
		if (config.getEnabled().getValue())
			startServer();
	}

	/**
	 * Reveals the AI activity window.
	 */
	public void showWindow() {
		McpFx.runAndWait(() -> {
			if (window == null) {
				McpActivityPane pane = new McpActivityPane(this);
				RecafStage stage = new RecafStage();
				stage.setTitle("Recaf · AI Assistant (MCP)");
				stage.setScene(new RecafScene(pane, 760, 620));
				stage.setMinWidth(560);
				stage.setMinHeight(380);
				stage.setOnCloseRequest(e -> stage.hide());
				window = stage;
			}
			window.show();
			window.toFront();
			window.requestFocus();
		});
	}

	/**
	 * Closes the activity window if it is open.
	 */
	public void hideWindow() {
		Stage stage = window;
		if (stage != null)
			FxThreadUtil.run(stage::hide);
	}

	/**
	 * Opens the given class in the main window, so the user can see it.
	 *
	 * @param name
	 * 		Class name.
	 *
	 * @return {@code true} when the class was opened.
	 */
	public boolean openClass(@Nonnull String name) {
		return ui.openClass(name, null) != null;
	}

	// ================================================================== accessors

	@Nonnull
	public McpConfig getConfig() {
		return config;
	}

	@Nonnull
	public McpServer getServer() {
		return server;
	}

	@Nonnull
	public McpPlatform getPlatform() {
		return platform;
	}

	@Nonnull
	public ObservableList<McpActivity> getActivities() {
		return activities;
	}

	@Nonnull
	public BooleanProperty runningProperty() {
		return running;
	}

	@Nonnull
	public StringProperty endpointProperty() {
		return endpoint;
	}

	@Nonnull
	public StringProperty statusMessageProperty() {
		return statusMessage;
	}

	@Nonnull
	public StringProperty lastViewedProperty() {
		return lastViewed;
	}

	/**
	 * @return JSON snippet that can be pasted into an MCP client configuration.
	 */
	@Nonnull
	public String clientConfig() {
		String token = config.getAccessToken().getValue();
		int port = server.isRunning() ? server.getPort() : config.getPort().getValue();
		String url = "http://127.0.0.1:" + port + McpServer.ENDPOINT_PATH;
		StringBuilder sb = new StringBuilder();
		sb.append("{\n");
		sb.append("  \"mcpServers\": {\n");
		sb.append("    \"recaf\": {\n");
		sb.append("      \"type\": \"http\",\n");
		sb.append("      \"url\": \"").append(url).append("\"");
		if (token != null && !token.isBlank())
			sb.append(",\n      \"headers\": { \"Authorization\": \"Bearer ").append(token).append("\" }");
		sb.append("\n    }\n");
		sb.append("  }\n");
		sb.append("}\n");
		return sb.toString();
	}

	// ================================================================== listener

	@Override
	public void onActivity(@Nonnull McpActivity activity) {
		FxThreadUtil.run(() -> {
			activities.add(activity);
			while (activities.size() > MAX_ACTIVITY)
				activities.remove(0);
			if (McpActivity.KIND_VIEW.equals(activity.kind()) && !activity.target().isEmpty())
				lastViewed.set(activity.target());
			else if (McpActivity.KIND_EDIT.equals(activity.kind()) && !activity.target().isEmpty())
				lastViewed.set(activity.target() + "  (editing)");
		});
	}

	@Override
	public void onStateChanged(boolean isRunning, @Nonnull String endpointUrl, @Nullable String error) {
		FxThreadUtil.run(() -> {
			running.set(isRunning);
			endpoint.set(endpointUrl);
			statusMessage.set(error != null ? error : (isRunning ? "Listening" : "Stopped"));
		});
	}

	@Nonnull
	@Override
	public List<McpActivity> recentActivities(int limit) {
		List<McpActivity> snapshot = McpFx.callAndWait(() -> new ArrayList<>(activities), new ArrayList<>());
		if (snapshot == null)
			return List.of();
		if (limit <= 0 || snapshot.size() <= limit)
			return snapshot;
		return snapshot.subList(snapshot.size() - limit, snapshot.size());
	}

	/**
	 * @return {@code true} when the application has an FX toolkit that can be used.
	 */
	public static boolean isFxReady() {
		try {
			Platform.runLater(() -> {
			});
			return true;
		} catch (Throwable t) {
			return false;
		}
	}
}
