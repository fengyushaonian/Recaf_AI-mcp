package software.coley.recaf.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import javafx.scene.Node;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import software.coley.bentofx.dockable.Dockable;
import software.coley.bentofx.layout.container.DockContainerLeaf;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.navigation.Actions;
import software.coley.recaf.services.navigation.ClassNavigable;
import software.coley.recaf.services.navigation.Navigable;
import software.coley.recaf.services.navigation.NavigationManager;
import software.coley.recaf.ui.control.richtext.Editor;
import software.coley.recaf.ui.docking.DockingManager;
import software.coley.recaf.ui.pane.editing.ClassPane;
import software.coley.recaf.ui.pane.editing.jvm.JvmDecompilerPane;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Glue between MCP tool calls and the live Recaf user interface.
 * <p>
 * Opening a class through this bridge is what makes the AI's work visible to the user, because
 * the same tab is opened and focused in the main window.
 *
 * @author Recaf AI-MCP
 */
public class McpUiBridge {
	private static final Logger logger = Logging.get(McpUiBridge.class);
	private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

	private final McpPlatform platform;
	private final Actions actions;
	private final DockingManager dockingManager;
	private final NavigationManager navigationManager;

	public McpUiBridge(@Nonnull McpPlatform platform,
	                   @Nonnull Actions actions,
	                   @Nonnull DockingManager dockingManager,
	                   @Nonnull NavigationManager navigationManager) {
		this.platform = platform;
		this.actions = actions;
		this.dockingManager = dockingManager;
		this.navigationManager = navigationManager;
	}

	/**
	 * Opens (and focuses) the tab of the given class in the main window.
	 *
	 * @param name
	 * 		Class name.
	 * @param member
	 * 		Optional member name to scroll to.
	 *
	 * @return Class name that was opened, or {@code null} when the class could not be opened.
	 */
	@Nullable
	public String openClass(@Nonnull String name, @Nullable String member) {
		ClassPathNode path = platform.resolve(name);
		if (path == null)
			return null;
		String className = path.getValue().getName();
		boolean opened = McpFx.runAndWait(() -> {
			try {
				ClassNavigable navigable = actions.gotoDeclaration(path);
				if (navigable == null)
					return;
				navigable.requestFocus();
				if (member != null && !member.isBlank()) {
					ClassMember target = findMember(path.getValue(), member);
					if (target != null)
						navigable.requestFocus(target);
				}
			} catch (Throwable t) {
				logger.warn("Failed to open class '{}' in the UI", className, t);
			}
		});
		return opened ? className : null;
	}

	/**
	 * @param info
	 * 		Class to search.
	 * @param member
	 * 		Member name, optionally followed by a descriptor.
	 *
	 * @return Matching member, or {@code null}.
	 */
	@Nullable
	private static ClassMember findMember(@Nonnull ClassInfo info, @Nonnull String member) {
		String needle = member.trim();
		for (MethodMember method : info.getMethods()) {
			if (method.getName().equals(needle)
					|| (method.getName() + method.getDescriptor()).equals(needle))
				return method;
		}
		for (FieldMember field : info.getFields()) {
			if (field.getName().equals(needle)
					|| (field.getName() + " " + field.getDescriptor()).equals(needle))
				return field;
		}
		return null;
	}

	/**
	 * @return Description of the user's current editor state, as JSON.
	 */
	@Nonnull
	public String currentView() {
		String json = McpFx.callAndWait(this::describeCurrentView, "{}");
		return json == null ? "{}" : json;
	}

	@Nonnull
	private String describeCurrentView() {
		Map<String, Object> view = new LinkedHashMap<>();
		ClassPathNode active = activeClassPath();
		view.put("activeClass", active == null ? null : active.getValue().getName());

		if (active != null) {
			Map<String, Object> caret = new LinkedHashMap<>();
			Editor editor = activeEditor();
			if (editor != null) {
				CodeArea area = editor.getCodeArea();
				caret.put("line", area.getCurrentParagraph() + 1);
				caret.put("position", area.getCaretPosition());
				String selected = area.getSelectedText();
				caret.put("selectionLength", selected == null ? 0 : selected.length());
				caret.put("selection", selected == null ? "" : limit(selected, 4000));
			}
			view.put("caret", caret);
		}

		List<String> tabs = new ArrayList<>();
		try {
			for (Navigable child : navigationManager.getNavigableChildren()) {
				PathNode<?> path = child.getPath();
				if (path instanceof ClassPathNode classPath)
					tabs.add(classPath.getValue().getName());
			}
		} catch (Throwable t) {
			logger.debug("Failed to collect open tabs", t);
		}
		view.put("openClasses", tabs);

		return GSON.toJson(view);
	}

	@Nullable
	private ClassPathNode activeClassPath() {
		DockContainerLeaf leaf = dockingManager.getPrimaryDockingContainer();
		if (leaf == null)
			return null;
		Dockable selected = leaf.getSelectedDockable();
		if (selected == null)
			return null;
		Node node = selected.getNode();
		if (node instanceof Navigable navigable) {
			PathNode<?> path = navigable.getPath();
			if (path instanceof ClassPathNode classPath)
				return classPath;
		}
		return null;
	}

	/**
	 * @return Editor of the selected class tab, or {@code null}.
	 */
	@Nullable
	private Editor activeEditor() {
		DockContainerLeaf leaf = dockingManager.getPrimaryDockingContainer();
		if (leaf == null)
			return null;
		Dockable selected = leaf.getSelectedDockable();
		if (selected == null)
			return null;
		Node node = selected.getNode();
		if (node instanceof ClassPane classPane) {
			Node display = classPane.getDisplay();
			if (display instanceof JvmDecompilerPane decompilerPane)
				return decompilerPane.getEditor();
		}
		return null;
	}

	@Nonnull
	private static String limit(@Nonnull String text, int max) {
		return text.length() <= max ? text : text.substring(0, max);
	}
}
