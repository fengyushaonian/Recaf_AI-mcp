package software.coley.recaf.mcp;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.observables.ObservableBoolean;
import software.coley.observables.ObservableInteger;
import software.coley.observables.ObservableString;
import software.coley.recaf.config.BasicConfigContainer;
import software.coley.recaf.config.BasicConfigValue;
import software.coley.recaf.config.ConfigGroups;
import software.coley.recaf.services.ServiceConfig;

/**
 * Configuration for the AI/MCP bridge.
 * <p>
 * The bridge exposes the live Recaf workspace to external AI agents over the
 * <a href="https://modelcontextprotocol.io">Model Context Protocol</a>.
 *
 * @author Recaf AI-MCP
 */
@ApplicationScoped
public class McpConfig extends BasicConfigContainer implements ServiceConfig {
	public static final String ID = "ai-mcp";
	public static final int DEFAULT_PORT = 18765;

	private final ObservableBoolean enabled = new ObservableBoolean(true);
	private final ObservableInteger port = new ObservableInteger(DEFAULT_PORT);
	private final ObservableBoolean autoShowWindow = new ObservableBoolean(true);
	private final ObservableBoolean openViewedClasses = new ObservableBoolean(true);
	private final ObservableBoolean trackSelection = new ObservableBoolean(true);
	private final ObservableString accessToken = new ObservableString("");

	@Inject
	public McpConfig() {
		super(ConfigGroups.SERVICE, ID);
		addValue(new BasicConfigValue<>("enabled", boolean.class, enabled));
		addValue(new BasicConfigValue<>("port", int.class, port));
		addValue(new BasicConfigValue<>("auto-show-window", boolean.class, autoShowWindow));
		addValue(new BasicConfigValue<>("open-viewed-classes", boolean.class, openViewedClasses));
		addValue(new BasicConfigValue<>("track-selection", boolean.class, trackSelection));
		addValue(new BasicConfigValue<>("access-token", String.class, accessToken));
	}

	/**
	 * @return {@code true} to start the MCP server when Recaf launches.
	 */
	@Nonnull
	public ObservableBoolean getEnabled() {
		return enabled;
	}

	/**
	 * @return Port the MCP server binds to on the loopback interface.
	 */
	@Nonnull
	public ObservableInteger getPort() {
		return port;
	}

	/**
	 * @return {@code true} to show the AI activity window on launch.
	 */
	@Nonnull
	public ObservableBoolean getAutoShowWindow() {
		return autoShowWindow;
	}

	/**
	 * @return {@code true} to open the class tab in the UI when an AI agent inspects a class.
	 * This is what lets the user watch the same code the AI is looking at.
	 */
	@Nonnull
	public ObservableBoolean getOpenViewedClasses() {
		return openViewedClasses;
	}

	/**
	 * @return {@code true} to report the user's caret/selection position back to AI agents.
	 */
	@Nonnull
	public ObservableBoolean getTrackSelection() {
		return trackSelection;
	}

	/**
	 * @return Optional bearer token required on every request. Empty means no authentication.
	 */
	@Nonnull
	public ObservableString getAccessToken() {
		return accessToken;
	}
}
