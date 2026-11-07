package software.coley.recaf.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Model Context Protocol server exposing the live Recaf workspace to AI agents.
 * <p>
 * Supports both transports used in the wild:
 * <ul>
 *     <li>Streamable HTTP - {@code POST /mcp} with JSON responses.</li>
 *     <li>HTTP + SSE <i>(legacy)</i> - {@code GET /sse} and {@code POST /message?sessionId=...}.</li>
 * </ul>
 * The server only binds to the loopback interface.
 *
 * @author Recaf AI-MCP
 */
public class McpServer {
	public static final String PROTOCOL_VERSION = "2025-06-18";
	public static final String SERVER_NAME = "recaf-ai-mcp";
	public static final String SERVER_VERSION = "1.0.0";
	public static final String ENDPOINT_PATH = "/mcp";
	public static final String SSE_PATH = "/sse";
	public static final String MESSAGE_PATH = "/message";
	public static final String HEALTH_PATH = "/health";

	private static final Logger logger = Logging.get(McpServer.class);
	private static final Gson GSON = new GsonBuilder().serializeNulls().create();

	private final McpConfig config;
	private final McpPlatform platform;
	private final McpUiBridge ui;
	private final Listener listener;
	private final Map<String, SseSession> sessions = new ConcurrentHashMap<>();
	private final List<JsonObject> tools = new ArrayList<>();
	private HttpServer server;
	private ExecutorService executor;
	private volatile boolean running;
	private volatile int boundPort;
	private volatile String lastError;

	public McpServer(@Nonnull McpConfig config,
	                 @Nonnull McpPlatform platform,
	                 @Nonnull McpUiBridge ui,
	                 @Nonnull Listener listener) {
		this.config = config;
		this.platform = platform;
		this.ui = ui;
		this.listener = listener;
		this.boundPort = config.getPort().getValue();
		registerTools();
	}

	// ================================================================== lifecycle

	/**
	 * Starts the HTTP server on the loopback interface.
	 * <br>
	 * When the configured port is already taken <i>(For instance by a previously installed MCP plugin)</i>
	 * the next free port is used instead, so the bridge always comes up.
	 *
	 * @throws IOException
	 * 		When no port could be bound.
	 */
	public synchronized void start() throws IOException {
		if (running)
			return;

		ThreadFactory factory = new ThreadFactory() {
			private final AtomicInteger counter = new AtomicInteger();

			@Override
			public Thread newThread(@Nonnull Runnable r) {
				Thread thread = new Thread(r, "recaf-mcp-" + counter.incrementAndGet());
				thread.setDaemon(true);
				return thread;
			}
		};

		int requested = config.getPort().getValue();
		IOException failure = null;
		for (int offset = 0; offset < 10; offset++) {
			int candidate = requested + offset;
			HttpServer newServer;
			try {
				newServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate), 0);
			} catch (IOException ex) {
				failure = ex;
				continue;
			}

			newServer.createContext(ENDPOINT_PATH, this::handleMcp);
			newServer.createContext(SSE_PATH, this::handleSse);
			newServer.createContext(MESSAGE_PATH, this::handleMessage);
			newServer.createContext(HEALTH_PATH, this::handleHealth);
			newServer.createContext("/", this::handleRoot);

			ExecutorService newExecutor = Executors.newCachedThreadPool(factory);
			newServer.setExecutor(newExecutor);
			newServer.start();

			server = newServer;
			executor = newExecutor;
			boundPort = candidate;
			running = true;
			lastError = null;
			logger.info("Recaf MCP server listening on {}", getEndpoint());
			listener.onStateChanged(true, getEndpoint(), null);
			return;
		}

		throw failure == null ? new IOException("No free port available") : failure;
	}

	/**
	 * Stops the server and closes all SSE sessions.
	 */
	public synchronized void stop() {
		if (!running)
			return;
		for (SseSession session : sessions.values())
			session.close();
		sessions.clear();
		if (server != null)
			server.stop(0);
		if (executor != null)
			executor.shutdownNow();
		server = null;
		executor = null;
		running = false;
		logger.info("Recaf MCP server stopped");
		listener.onStateChanged(false, getEndpoint(), null);
	}

	/**
	 * Records a startup failure so the UI can display it.
	 *
	 * @param message
	 * 		Failure message.
	 */
	public void reportStartFailure(@Nonnull String message) {
		lastError = message;
		listener.onStateChanged(false, getEndpoint(), message);
	}

	public boolean isRunning() {
		return running;
	}

	public int getPort() {
		return boundPort;
	}

	@Nonnull
	public String getEndpoint() {
		return "http://127.0.0.1:" + getPort() + ENDPOINT_PATH;
	}

	@Nonnull
	public String getSseEndpoint() {
		return "http://127.0.0.1:" + getPort() + SSE_PATH;
	}

	@Nullable
	public String getLastError() {
		return lastError;
	}

	// ================================================================== HTTP

	private void handleRoot(@Nonnull HttpExchange exchange) throws IOException {
		sendText(exchange, 200, "Recaf MCP server\n" + getEndpoint() + "\n");
	}

	private void handleHealth(@Nonnull HttpExchange exchange) throws IOException {
		JsonObject json = new JsonObject();
		json.addProperty("status", "ok");
		json.addProperty("server", SERVER_NAME);
		json.addProperty("version", SERVER_VERSION);
		json.addProperty("endpoint", getEndpoint());
		json.addProperty("protocolVersion", PROTOCOL_VERSION);
		sendJson(exchange, 200, json);
	}

	private void handleMcp(@Nonnull HttpExchange exchange) throws IOException {
		try {
			String method = exchange.getRequestMethod();
			if ("GET".equalsIgnoreCase(method)) {
				// Streamable HTTP allows a GET stream for server->client messages. We do not push
				// any messages on our own, so we advertise the capability as unsupported.
				exchange.getResponseHeaders().add("Allow", "POST");
				sendText(exchange, 405, "Use POST for JSON-RPC. Server-initiated messages are not supported.");
				return;
			}
			if (!"POST".equalsIgnoreCase(method)) {
				exchange.getResponseHeaders().add("Allow", "POST");
				sendText(exchange, 405, "Method not allowed");
				return;
			}
			if (!checkAuth(exchange)) {
				sendText(exchange, 401, "Unauthorized");
				return;
			}

			String body = readBody(exchange);
			JsonElement parsed;
			try {
				parsed = JsonParser.parseString(body);
			} catch (Throwable t) {
				sendJson(exchange, 400, errorResponse(JsonNull.INSTANCE, -32700, "Parse error"));
				return;
			}

			if (parsed.isJsonArray()) {
				JsonArray responses = new JsonArray();
				for (JsonElement element : parsed.getAsJsonArray()) {
					JsonObject response = handleRequest(asObject(element));
					if (response != null)
						responses.add(response);
				}
				if (responses.isEmpty()) {
					sendEmpty(exchange, 202);
				} else {
					sendJson(exchange, 200, responses);
				}
				return;
			}

			JsonObject response = handleRequest(asObject(parsed));
			if (response == null) {
				sendEmpty(exchange, 202);
			} else {
				sendJson(exchange, 200, response);
			}
		} catch (Throwable t) {
			logger.error("MCP request handling failed", t);
			try {
				sendJson(exchange, 500, errorResponse(JsonNull.INSTANCE, -32603, "Internal error: " + t));
			} catch (Throwable ignored) {
				// Nothing we can do.
			}
		}
	}

	private void handleSse(@Nonnull HttpExchange exchange) throws IOException {
		if (!checkAuth(exchange)) {
			sendText(exchange, 401, "Unauthorized");
			return;
		}
		if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendText(exchange, 405, "Method not allowed");
			return;
		}

		String id = UUID.randomUUID().toString();
		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.getResponseHeaders().add("Cache-Control", "no-cache");
		exchange.getResponseHeaders().add("Connection", "keep-alive");
		exchange.sendResponseHeaders(200, 0);

		SseSession session = new SseSession(id, exchange.getResponseBody());
		sessions.put(id, session);
		try {
			session.sendRaw("event: endpoint\ndata: " + MESSAGE_PATH + "?sessionId=" + id + "\n\n");
			// Hold the handler thread open so the HTTP exchange stays alive while the client is connected.
			while (!session.isClosed()) {
				session.awaitClose(15_000L);
				if (!session.isClosed())
					session.sendRaw(": keep-alive\n\n");
			}
		} catch (Throwable t) {
			// Client disconnected.
		} finally {
			sessions.remove(id);
			session.close();
			try {
				exchange.close();
			} catch (Throwable ignored) {
				// Ignore.
			}
		}
	}

	private void handleMessage(@Nonnull HttpExchange exchange) throws IOException {
		if (!checkAuth(exchange)) {
			sendText(exchange, 401, "Unauthorized");
			return;
		}
		if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
			sendText(exchange, 405, "Method not allowed");
			return;
		}

		String query = exchange.getRequestURI().getQuery();
		String sessionId = queryParameter(query, "sessionId");
		SseSession session = sessionId == null ? null : sessions.get(sessionId);
		if (session == null) {
			sendText(exchange, 404, "Unknown session");
			return;
		}

		JsonObject response;
		try {
			response = handleRequest(asObject(JsonParser.parseString(readBody(exchange))));
		} catch (Throwable t) {
			sendText(exchange, 400, "Parse error");
			return;
		}
		if (response != null)
			session.sendMessage(response);
		sendEmpty(exchange, 202);
	}

	// ================================================================== JSON-RPC

	@Nullable
	private JsonObject handleRequest(@Nonnull JsonObject request) {
		JsonElement id = request.get("id");
		String method = request.has("method") ? request.get("method").getAsString() : "";
		JsonObject params = request.has("params") && request.get("params").isJsonObject()
				? request.getAsJsonObject("params") : new JsonObject();

		// Notifications never get a response.
		boolean notification = id == null || id.isJsonNull();

		try {
			JsonObject result = switch (method) {
				case "initialize" -> initialize(params);
				case "ping" -> new JsonObject();
				case "tools/list" -> listTools();
				case "tools/call" -> callTool(params);
				case "resources/list" -> emptyList("resources");
				case "resources/templates/list" -> emptyList("resourceTemplates");
				case "prompts/list" -> emptyList("prompts");
				case "notifications/initialized", "notifications/cancelled", "notifications/roots/list_changed" -> null;
				default -> throw new McpProtocolException(-32601, "Method not found: " + method);
			};

			if (notification)
				return null;
			if (result == null)
				return successResponse(id, new JsonObject());
			return successResponse(id, result);
		} catch (McpProtocolException ex) {
			if (notification)
				return null;
			return errorResponse(id, ex.code, ex.getMessage());
		} catch (Throwable t) {
			logger.error("MCP method '{}' failed", method, t);
			if (notification)
				return null;
			return errorResponse(id, -32603, "Internal error: " + t);
		}
	}

	@Nonnull
	private JsonObject initialize(@Nonnull JsonObject params) {
		String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL_VERSION;

		JsonObject capabilities = new JsonObject();
		JsonObject toolsCapability = new JsonObject();
		toolsCapability.addProperty("listChanged", false);
		capabilities.add("tools", toolsCapability);
		JsonObject resourcesCapability = new JsonObject();
		resourcesCapability.addProperty("subscribe", false);
		resourcesCapability.addProperty("listChanged", false);
		capabilities.add("resources", resourcesCapability);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", SERVER_NAME);
		serverInfo.addProperty("version", SERVER_VERSION);

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", requested == null || requested.isBlank() ? PROTOCOL_VERSION : requested);
		result.add("capabilities", capabilities);
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions", INSTRUCTIONS);
		return result;
	}

	private static final String INSTRUCTIONS = """
			You are connected to a running Recaf instance (Java bytecode editor).
			Workflow:
			1. Call 'workspace_info' to confirm a workspace is open.
			2. Use 'list_classes' / 'search_classes' / 'search_members' to locate targets.
			3. Call 'open_class' before editing. This opens the tab inside Recaf so the human user
			   can watch exactly which code you are reading.
			4. For JVM classes use 'apply_source' with the full updated Java source. It is compiled
			   in-process and written into the workspace. Do not run any external build afterwards.
			   For Android/DEX classes the only readable form is Dalvik assembly: use 'get_assembly'
			   and write it back with 'apply_assembly' (no compiler involved).
			5. Use 'get_bytes' / 'replace_bytes' for raw JVM class file work.
			Every edit response contains a unified diff which is also shown to the user in the Recaf
			'AI activity' window, so keep exploration calls focused.
			Notes:
			- Class names may be given as 'a.b.C' or 'a/b/C'.
			- Tool calls are displayed live in the Recaf UI, alongside the exact code you read.
			""";

	@Nonnull
	private JsonObject listTools() {
		JsonArray array = new JsonArray();
		for (JsonObject tool : tools)
			array.add(tool);
		JsonObject result = new JsonObject();
		result.add("tools", array);
		return result;
	}

	@Nonnull
	private JsonObject callTool(@Nonnull JsonObject params) {
		String name = params.has("name") ? params.get("name").getAsString() : "";
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
				? params.getAsJsonObject("arguments") : new JsonObject();

		long start = System.currentTimeMillis();
		McpToolResult result;
		McpActivity activity = null;
		try {
			switch (name) {
				case "workspace_info" -> result = platform.workspaceInfo();
				case "list_classes" -> result = platform.listClasses(stringArg(arguments, "query", ""), intArg(arguments, "limit", 200));
				case "search_classes" -> result = platform.searchClasses(stringArg(arguments, "regex", ""), intArg(arguments, "limit", 100));
				case "search_members" -> result = platform.searchMembers(stringArg(arguments, "query", ""), intArg(arguments, "limit", 100));
				case "current_view" -> result = McpToolResult.ok(ui.currentView());
				case "open_class" -> result = openClass(name, stringArg(arguments, "name", ""), stringArg(arguments, "member", null));
				case "apply_source" -> result = platform.applySource(stringArg(arguments, "name", ""), stringArg(arguments, "source", ""));
				case "get_assembly" -> result = getAssembly(stringArg(arguments, "name", ""));
				case "apply_assembly" -> result = platform.applyAssembly(stringArg(arguments, "name", ""), stringArg(arguments, "text", ""));
				case "get_bytes" -> result = platform.classBytes(stringArg(arguments, "name", ""));
				case "replace_bytes" -> result = platform.replaceBytes(stringArg(arguments, "name", ""), stringArg(arguments, "base64", ""));
				case "recent_activity" -> result = recentActivity(intArg(arguments, "limit", 25));
				default -> result = McpToolResult.fail("Unknown tool: " + name);
			}
		} catch (Throwable t) {
			logger.error("MCP tool '{}' failed", name, t);
			result = McpToolResult.fail("Tool failed: " + t);
		}

		// Report the action to the UI so the user can watch.
		activity = buildActivity(name, arguments, result);
		if (activity != null)
			listener.onActivity(activity);
		logger.debug("MCP tool {} finished in {}ms", name, System.currentTimeMillis() - start);

		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", result.text());
		JsonArray contentArray = new JsonArray();
		contentArray.add(content);

		JsonObject toolResult = new JsonObject();
		toolResult.add("content", contentArray);
		toolResult.addProperty("isError", result.error());
		return toolResult;
	}

	@Nonnull
	private McpToolResult openClass(@Nonnull String tool, @Nonnull String name, @Nullable String member) {
		if (name.isBlank())
			return McpToolResult.fail("Missing 'name' argument.");

		// Resolve first so that we know whether to present Java source or Dalvik assembly.
		software.coley.recaf.path.ClassPathNode path = platform.resolve(name);
		if (path == null)
			return McpToolResult.fail("Class not found in workspace: " + name);

		if (!path.getValue().isJvmClass()) {
			McpPlatform.AssemblyView assembly = platform.assembly(path.getValue().getName());
			if (assembly == null)
				return McpToolResult.fail("Class not found in workspace: " + name);
			if (!assembly.isSuccess())
				return McpToolResult.fail("Failed to disassemble '" + assembly.info().getName() + "':\n" + assembly.error());

			String opened = ui.openClass(assembly.info().getName(), member);
			StringBuilder sb = new StringBuilder();
			appendClassHeader(sb, assembly.info(), opened, member);
			sb.append("Format: Dalvik assembly (no Java decompiler is bundled for DEX classes; ")
					.append("edit with 'apply_assembly')\n");
			sb.append("----------------------------------------------------------------\n");
			sb.append(assembly.text());
			return McpToolResult.ok(sb.toString());
		}

		McpPlatform.SourceView view = platform.source(path.getValue().getName());
		if (view == null)
			return McpToolResult.fail("Class not found in workspace: " + name);
		if (!view.isSuccess())
			return McpToolResult.fail("Failed to decompile '" + view.info().getName() + "': " + view.error());

		String opened = ui.openClass(view.info().getName(), member);
		StringBuilder sb = new StringBuilder();
		appendClassHeader(sb, view.info(), opened, member);
		sb.append("----------------------------------------------------------------\n");
		sb.append(view.source());
		return McpToolResult.ok(sb.toString());
	}

	/**
	 * Appends the shared class header block.
	 *
	 * @param sb
	 * 		Builder to append to.
	 * @param info
	 * 		Class being shown.
	 * @param opened
	 * 		Name of the class opened in the UI, or {@code null}.
	 * @param member
	 * 		Optional focused member name.
	 */
	private static void appendClassHeader(@Nonnull StringBuilder sb,
	                                      @Nonnull software.coley.recaf.info.ClassInfo info,
	                                      @Nullable String opened,
	                                      @Nullable String member) {
		sb.append("Class: ").append(info.getName()).append('\n');
		sb.append("Fields: ").append(info.getFields().size())
				.append(", Methods: ").append(info.getMethods().size())
				.append(", Kind: ").append(info.isJvmClass() ? "jvm" : "android");
		if (info.isJvmClass())
			sb.append(", Version: ").append(info.asJvmClass().getVersion());
		sb.append('\n');
		if (member != null && !member.isBlank())
			sb.append("Focused member: ").append(member).append('\n');
		sb.append("Opened in Recaf UI: ").append(opened != null ? "yes" : "no").append('\n');
	}

	@Nonnull
	private McpToolResult getAssembly(@Nonnull String name) {
		if (name.isBlank())
			return McpToolResult.fail("Missing 'name' argument.");

		McpPlatform.AssemblyView assembly = platform.assembly(name);
		if (assembly == null)
			return McpToolResult.fail("Class not found in workspace: " + name);
		if (!assembly.isSuccess())
			return McpToolResult.fail("Failed to disassemble '" + assembly.info().getName() + "':\n" + assembly.error());

		StringBuilder sb = new StringBuilder();
		appendClassHeader(sb, assembly.info(), null, null);
		sb.append("Format: ").append(assembly.isJvm() ? "JVM assembler" : "Dalvik assembler").append('\n');
		sb.append("----------------------------------------------------------------\n");
		sb.append(assembly.text());
		return McpToolResult.ok(sb.toString());
	}

	@Nonnull
	private McpToolResult recentActivity(int limit) {
		List<McpActivity> activities = listener.recentActivities(clamp(limit, 0, 200));
		JsonArray array = new JsonArray();
		for (McpActivity activity : activities) {
			JsonObject json = new JsonObject();
			json.addProperty("time", activity.timeText());
			json.addProperty("kind", activity.kind());
			json.addProperty("tool", activity.tool());
			json.addProperty("target", activity.target());
			json.addProperty("summary", activity.summary());
			json.addProperty("error", activity.error());
			array.add(json);
		}
		JsonObject result = new JsonObject();
		result.add("activities", array);
		return McpToolResult.ok(GSON.toJson(result));
	}

	@Nullable
	private McpActivity buildActivity(@Nonnull String tool, @Nonnull JsonObject arguments, @Nonnull McpToolResult result) {
		String target = stringArg(arguments, "name", "");
		String detail = "";
		String summary;
		String kind;

		switch (tool) {
			case "open_class" -> {
				kind = McpActivity.KIND_VIEW;
				summary = "Viewing " + target;
				detail = result.error() ? result.text() : truncate(result.text(), 60_000);
			}
			case "apply_source", "apply_assembly", "replace_bytes" -> {
				kind = McpActivity.KIND_EDIT;
				String diff = "";
				String message = "";
				if (!result.error()) {
					try {
						JsonObject json = JsonParser.parseString(result.text()).getAsJsonObject();
						diff = json.has("diff") ? json.get("diff").getAsString() : "";
						message = json.has("summary") ? json.get("summary").getAsString() : "";
					} catch (Throwable ignored) {
						message = "Modified " + target;
					}
				}
				summary = result.error() ? "Edit failed: " + target : (message.isEmpty() ? "Modified " + target : message);
				detail = result.error() ? result.text() : diff;
			}
			case "get_bytes", "get_assembly", "list_classes", "search_classes", "search_members",
				 "workspace_info", "current_view", "recent_activity" -> {
				kind = McpActivity.KIND_QUERY;
				summary = tool + (target.isEmpty() ? "" : ": " + target);
				detail = truncate(result.text(), 20_000);
			}
			default -> {
				return null;
			}
		}

		return new McpActivity(System.currentTimeMillis(), kind, tool, target, summary, detail, result.error());
	}

	// ================================================================== tool definitions

	private void registerTools() {
		tools.add(tool("workspace_info",
				"Overview of the currently opened Recaf workspace: primary resource, supporting resources, class/file counts and compiler availability.",
				"""
						{"type":"object","properties":{},"additionalProperties":false}
						"""));
		tools.add(tool("list_classes",
				"List classes in the workspace. Optional substring filter on the internal class name.",
				"""
						{"type":"object","properties":{
						  "query":{"type":"string","description":"Case-insensitive substring matched against class names such as 'net/minecraft'."},
						  "limit":{"type":"integer","description":"Maximum number of classes to return. Default 200."}
						},"additionalProperties":false}
						"""));
		tools.add(tool("search_classes",
				"Search class names with a regular expression.",
				"""
						{"type":"object","properties":{
						  "regex":{"type":"string","description":"Java regular expression matched against internal class names."},
						  "limit":{"type":"integer","description":"Maximum number of results. Default 100."}
						},"required":["regex"],"additionalProperties":false}
						"""));
		tools.add(tool("search_members",
				"Search field and method names across all classes in the workspace.",
				"""
						{"type":"object","properties":{
						  "query":{"type":"string","description":"Case-insensitive substring matched against member names."},
						  "limit":{"type":"integer","description":"Maximum number of results. Default 100."}
						},"required":["query"],"additionalProperties":false}
						"""));
		tools.add(tool("open_class",
				"Open a class inside the Recaf window so the human user can see exactly which code you are reading, and return its readable form: Java source for JVM classes, Dalvik assembly for Android/DEX classes (this build has no DEX decompiler). Always call this before editing a class.",
				"""
						{"type":"object","properties":{
						  "name":{"type":"string","description":"Class name, dotted ('a.b.C') or internal ('a/b/C')."},
						  "member":{"type":"string","description":"Optional field/method name to scroll to."}
						},"required":["name"],"additionalProperties":false}
						"""));
		tools.add(tool("current_view",
				"Report what the human user currently has open in Recaf: active class tab, caret line, and the currently selected text.",
				"""
						{"type":"object","properties":{},"additionalProperties":false}
						"""));
		tools.add(tool("apply_source",
				"Compile the given Java source in-process and write the resulting class(es) into the workspace. No external build is required. Returns a unified diff, which is also displayed to the user.",
				"""
						{"type":"object","properties":{
						  "name":{"type":"string","description":"Class name being replaced."},
						  "source":{"type":"string","description":"Complete Java source of the class."}
						},"required":["name","source"],"additionalProperties":false}
						"""));
		tools.add(tool("get_assembly",
				"Disassemble a class into Recaf's assembler format (JVM assembler, or Dalvik assembler for Android/DEX classes). This is the only readable form for APK classes in this build, and is required before using 'apply_assembly'.",
				"""
						{"type":"object","properties":{
						  "name":{"type":"string","description":"Class name."}
						},"required":["name"],"additionalProperties":false}
						"""));
		tools.add(tool("apply_assembly",
				"Assemble the given assembler text and write the resulting class back into the workspace. Works for JVM classes and Android/DEX classes. No external compiler or local build is involved. Returns a unified diff which is also shown to the user.",
				"""
						{"type":"object","properties":{
						  "name":{"type":"string","description":"Class name being modified."},
						  "text":{"type":"string","description":"Complete assembler text of the class (as produced by get_assembly, with your edits applied)."}
						},"required":["name","text"],"additionalProperties":false}
						"""));
		tools.add(tool("get_bytes",
				"Read the raw class file bytes as base64.",
				"""
						{"type":"object","properties":{
						  "name":{"type":"string","description":"Class name."}
						},"required":["name"],"additionalProperties":false}
						"""));
		tools.add(tool("replace_bytes",
				"Replace a class with raw class file bytes (base64). Use this when you cannot express the change in Java source.",
				"""
						{"type":"object","properties":{
						  "name":{"type":"string","description":"Class name being replaced."},
						  "base64":{"type":"string","description":"Base64 encoded class file bytes."}
						},"required":["name","base64"],"additionalProperties":false}
						"""));
		tools.add(tool("recent_activity",
				"List recent AI actions recorded by the Recaf MCP bridge.",
				"""
						{"type":"object","properties":{
						  "limit":{"type":"integer","description":"Maximum entries. Default 25."}
						},"additionalProperties":false}
						"""));
	}

	@Nonnull
	private static JsonObject tool(@Nonnull String name, @Nonnull String description, @Nonnull String schema) {
		JsonObject tool = new JsonObject();
		tool.addProperty("name", name);
		tool.addProperty("description", description);
		tool.add("inputSchema", JsonParser.parseString(schema).getAsJsonObject());
		return tool;
	}

	// ================================================================== helpers

	private boolean checkAuth(@Nonnull HttpExchange exchange) {
		String token = config.getAccessToken().getValue();
		if (token == null || token.isBlank())
			return true;
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header != null && header.equalsIgnoreCase("Bearer " + token))
			return true;
		String query = queryParameter(exchange.getRequestURI().getQuery(), "token");
		return token.equals(query);
	}

	@Nonnull
	private static String readBody(@Nonnull HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) != -1)
				out.write(buffer, 0, read);
			return out.toString(StandardCharsets.UTF_8);
		}
	}

	private static void sendJson(@Nonnull HttpExchange exchange, int code, @Nonnull JsonElement json) throws IOException {
		byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void sendText(@Nonnull HttpExchange exchange, int code, @Nonnull String text) throws IOException {
		byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
		exchange.sendResponseHeaders(code, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void sendEmpty(@Nonnull HttpExchange exchange, int code) throws IOException {
		exchange.sendResponseHeaders(code, -1);
		exchange.close();
	}

	@Nonnull
	private static JsonObject successResponse(@Nonnull JsonElement id, @Nonnull JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		return response;
	}

	@Nonnull
	private static JsonObject errorResponse(@Nonnull JsonElement id, int code, @Nonnull String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("error", error);
		return response;
	}

	@Nonnull
	private static JsonObject emptyList(@Nonnull String key) {
		JsonObject result = new JsonObject();
		result.add(key, new JsonArray());
		return result;
	}

	@Nonnull
	private static JsonObject asObject(@Nonnull JsonElement element) {
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	@Nonnull
	private static String stringArg(@Nonnull JsonObject arguments, @Nonnull String key, @Nullable String fallback) {
		if (!arguments.has(key) || arguments.get(key).isJsonNull())
			return fallback == null ? "" : fallback;
		return arguments.get(key).getAsString();
	}

	private static int intArg(@Nonnull JsonObject arguments, @Nonnull String key, int fallback) {
		try {
			return arguments.has(key) && !arguments.get(key).isJsonNull() ? arguments.get(key).getAsInt() : fallback;
		} catch (Throwable t) {
			return fallback;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	@Nonnull
	private static String truncate(@Nonnull String text, int max) {
		return text.length() <= max ? text : text.substring(0, max) + "\n... truncated ...";
	}

	@Nullable
	private static String queryParameter(@Nullable String query, @Nonnull String key) {
		if (query == null)
			return null;
		for (String pair : query.split("&")) {
			int index = pair.indexOf('=');
			if (index <= 0)
				continue;
			if (pair.substring(0, index).equals(key))
				return pair.substring(index + 1);
		}
		return null;
	}

	// ================================================================== types

	/**
	 * Callbacks used to surface server state and actions to the UI.
	 */
	public interface Listener {
		/**
		 * @param activity
		 * 		Action that was just performed.
		 */
		void onActivity(@Nonnull McpActivity activity);

		/**
		 * @param running
		 * 		Whether the server is now running.
		 * @param endpoint
		 * 		Current endpoint URL.
		 * @param error
		 * 		Error message when the state change was a failure.
		 */
		void onStateChanged(boolean running, @Nonnull String endpoint, @Nullable String error);

		/**
		 * @param limit
		 * 		Maximum number of entries to fetch, newest last.
		 *
		 * @return Recent actions, oldest first.
		 */
		@Nonnull
		List<McpActivity> recentActivities(int limit);
	}

	private static class McpProtocolException extends RuntimeException {
		private final int code;

		McpProtocolException(int code, @Nonnull String message) {
			super(message);
			this.code = code;
		}
	}

	private static class SseSession {
		private final String id;
		private final OutputStream out;
		private volatile boolean closed;
		private final Object lock = new Object();

		SseSession(@Nonnull String id, @Nonnull OutputStream out) {
			this.id = id;
			this.out = out;
		}

		synchronized void sendRaw(@Nonnull String text) {
			if (closed) return;
			try {
				out.write(text.getBytes(StandardCharsets.UTF_8));
				out.flush();
			} catch (IOException ex) {
				closed = true;
				notifyAllQuietly();
			}
		}

		void sendMessage(@Nonnull JsonElement message) {
			sendRaw("event: message\ndata: " + GSON.toJson(message) + "\n\n");
		}

		boolean isClosed() {
			return closed;
		}

		void close() {
			if (closed) return;
			closed = true;
			notifyAllQuietly();
			try {
				out.close();
			} catch (Throwable ignored) {
				// Ignore.
			}
		}

		void awaitClose(long millis) {
			synchronized (lock) {
				try {
					lock.wait(millis);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					closed = true;
				}
			}
		}

		private void notifyAllQuietly() {
			synchronized (lock) {
				lock.notifyAll();
			}
		}
	}
}
