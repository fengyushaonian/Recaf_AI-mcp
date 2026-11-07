package software.coley.recaf.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.error.Diagnostic;
import me.darknet.assembler.error.Outcome;
import me.darknet.assembler.parser.Token;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import software.coley.collections.Unchecked;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.compile.CompileMap;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.compile.CompilerResult;
import software.coley.recaf.services.compile.JavacArgumentsBuilder;
import software.coley.recaf.services.compile.JavacCompiler;
import software.coley.recaf.services.compile.JavacCompilerConfig;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.util.JavaVersion;
import software.coley.recaf.workspace.WorkspaceSaveService;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Workspace-facing operations exposed to AI agents.
 * <p>
 * This type contains no MCP protocol logic, only the "what can the agent do to the workspace" part.
 *
 * @author Recaf AI-MCP
 */
public class McpPlatform {
	private static final Logger logger = Logging.get(McpPlatform.class);
	private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
	private static final int MAX_SOURCE_CHARS = 400_000;

	private final McpConfig config;
	private final WorkspaceManager workspaceManager;
	private final DecompilerManager decompilerManager;
	private final AssemblerPipelineManager pipelineManager;
	private final JavacCompiler javac;
	private final JavacCompilerConfig javacConfig;
	private final WorkspaceSaveService saveService;

	public McpPlatform(@Nonnull McpConfig config,
	                   @Nonnull WorkspaceManager workspaceManager,
	                   @Nonnull DecompilerManager decompilerManager,
	                   @Nonnull AssemblerPipelineManager pipelineManager,
	                   @Nonnull JavacCompiler javac,
	                   @Nonnull JavacCompilerConfig javacConfig,
	                   @Nonnull WorkspaceSaveService saveService) {
		this.config = config;
		this.workspaceManager = workspaceManager;
		this.decompilerManager = decompilerManager;
		this.pipelineManager = pipelineManager;
		this.javac = javac;
		this.javacConfig = javacConfig;
		this.saveService = saveService;
	}

	// ================================================================== queries

	/**
	 * @return Overview of the currently opened workspace.
	 */
	@Nonnull
	public McpToolResult workspaceInfo() {
		Workspace workspace = workspaceManager.getCurrent();
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("open", workspaceManager.hasCurrentWorkspace());
		info.put("primaryResource", describeResource(workspace.getPrimaryResource()));
		List<String> supporting = new ArrayList<>();
		for (WorkspaceResource resource : workspace.getSupportingResources())
			supporting.add(describeResource(resource));
		info.put("supportingResources", supporting);
		info.put("classCount", (int) workspace.classesStream(false).count());
		info.put("fileCount", (int) workspace.filesStream().count());
		info.put("javacAvailable", JavacCompiler.isAvailable());
		return McpToolResult.ok(GSON.toJson(info));
	}

	/**
	 * @param query
	 * 		Case-insensitive substring filter. Empty for no filter.
	 * @param limit
	 * 		Max number of classes to list.
	 *
	 * @return Class listing.
	 */
	@Nonnull
	public McpToolResult listClasses(@Nullable String query, int limit) {
		Workspace workspace = workspaceManager.getCurrent();
		String needle = query == null ? "" : query.toLowerCase();
		int max = clamp(limit, 1, 2000);

		List<Object> classes = new ArrayList<>();
		workspace.classesStream(false)
				.map(ClassPathNode::getValue)
				.filter(info -> needle.isEmpty() || info.getName().toLowerCase().contains(needle))
				.sorted(Comparator.comparing(ClassInfo::getName))
				.limit(max)
				.forEach(info -> classes.add(classSummary(info)));

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", classes.size());
		result.put("classes", classes);
		return McpToolResult.ok(GSON.toJson(result));
	}

	/**
	 * @param regex
	 * 		Regular expression matched against class internal names.
	 * @param limit
	 * 		Max results.
	 *
	 * @return Matching class names.
	 */
	@Nonnull
	public McpToolResult searchClasses(@Nullable String regex, int limit) {
		if (regex == null || regex.isBlank())
			return McpToolResult.fail("Missing 'regex' argument.");

		Pattern pattern;
		try {
			pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		} catch (PatternSyntaxException ex) {
			return McpToolResult.fail("Invalid regex: " + ex.getDescription());
		}

		int max = clamp(limit, 1, 500);
		List<String> matches = workspaceManager.getCurrent().classesStream(false)
				.map(ClassPathNode::getValue)
				.map(ClassInfo::getName)
				.filter(name -> pattern.matcher(name).find())
				.sorted()
				.limit(max)
				.toList();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", matches.size());
		result.put("classes", matches);
		return McpToolResult.ok(GSON.toJson(result));
	}

	/**
	 * @param needle
	 * 		Case-insensitive substring matched against field/method names.
	 * @param limit
	 * 		Max results.
	 *
	 * @return Matching members.
	 */
	@Nonnull
	public McpToolResult searchMembers(@Nullable String needle, int limit) {
		if (needle == null || needle.isBlank())
			return McpToolResult.fail("Missing 'query' argument.");

		String match = needle.toLowerCase();
		int max = clamp(limit, 1, 500);
		List<Map<String, Object>> matches = new ArrayList<>();
		workspaceManager.getCurrent().classesStream(false)
				.map(ClassPathNode::getValue)
				.sorted(Comparator.comparing(ClassInfo::getName))
				.forEach(info -> {
					if (matches.size() >= max) return;
					for (FieldMember field : info.getFields()) {
						if (matches.size() >= max) return;
						if (field.getName().toLowerCase().contains(match))
							matches.add(memberEntry(info, "field", field.getName(), field.getDescriptor()));
					}
					for (MethodMember method : info.getMethods()) {
						if (matches.size() >= max) return;
						if (method.getName().toLowerCase().contains(match))
							matches.add(memberEntry(info, "method", method.getName(), method.getDescriptor()));
					}
				});

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", matches.size());
		result.put("members", matches);
		return McpToolResult.ok(GSON.toJson(result));
	}

	/**
	 * Decompiles the given class.
	 *
	 * @param name
	 * 		Class name, either {@code a.b.C} or {@code a/b/C}.
	 *
	 * @return Decompiled source, or a failure message.
	 */
	@Nonnull
	public SourceView source(@Nonnull String name) {
		ClassPathNode path = resolve(name);
		if (path == null)
			return null;

		ClassInfo info = path.getValue();
		Workspace workspace = path.getValueOfType(Workspace.class);
		if (workspace == null)
			return null;

		try {
			DecompileResult result = info.isJvmClass()
					? decompilerManager.decompile(workspace, info.asJvmClass()).get(45, TimeUnit.SECONDS)
					: decompilerManager.decompile(workspace, info.asAndroidClass()).get(45, TimeUnit.SECONDS);
			String text = result.getText();
			if (text == null)
				return new SourceView(info, null, decompileFailure(info, result));
			if (text.length() > MAX_SOURCE_CHARS)
				text = text.substring(0, MAX_SOURCE_CHARS) + "\n// ... truncated by Recaf MCP ...";
			return new SourceView(info, text, null);
		} catch (Exception ex) {
			return new SourceView(info, null, ex);
		}
	}

	/**
	 * Builds a human readable explanation for an empty decompilation result.
	 *
	 * @param info
	 * 		Class that was decompiled.
	 * @param result
	 * 		Result that did not contain any text.
	 *
	 * @return Explanation.
	 */
	@Nonnull
	private Exception decompileFailure(@Nonnull ClassInfo info, @Nonnull DecompileResult result) {
		if (result.getException() != null)
			return new IllegalStateException(result.getException().toString(), result.getException());

		String decompilerName = info.isJvmClass()
				? decompilerManager.getTargetJvmDecompiler().getName()
				: decompilerManager.getTargetAndroidDecompiler().getName();
		String hint = info.isAndroidClass()
				? " No Android/Dalvik decompiler is bundled in this build, so DEX classes are only available as Dalvik assembly. Use 'get_assembly' / 'apply_assembly' instead."
				: "";
		return new IllegalStateException("Decompiler '" + decompilerName + "' produced no output (" +
				result.getType() + ")." + hint);
	}

	/**
	 * @param name
	 * 		Class name.
	 *
	 * @return Base64 encoded class bytes.
	 */
	@Nonnull
	public McpToolResult classBytes(@Nonnull String name) {
		ClassPathNode path = resolve(name);
		if (path == null)
			return McpToolResult.fail("Class not found in workspace: " + name);
		if (!path.getValue().isJvmClass())
			return McpToolResult.fail("Raw byte access is only supported for JVM classes. " +
					"'" + name + "' lives in a DEX container.");

		JvmClassInfo info = path.getValue().asJvmClass();
		byte[] bytecode = info.getBytecode();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("name", info.getName());
		result.put("size", bytecode.length);
		result.put("base64", Base64.getEncoder().encodeToString(bytecode));
		return McpToolResult.ok(GSON.toJson(result));
	}

	/**
	 * Disassembles the given class into Recaf's assembler format.
	 * <p>
	 * This works for both JVM classes and Android/DEX classes, and is the only readable representation of
	 * DEX classes since this build does not bundle a Java decompiler for Dalvik.
	 *
	 * @param name
	 * 		Class name.
	 *
	 * @return Disassembled text, or {@code null} when the class is not in the workspace.
	 */
	@Nullable
	public AssemblyView assembly(@Nonnull String name) {
		ClassPathNode path = resolve(name);
		if (path == null)
			return null;

		ClassInfo info = path.getValue();
		try {
			AssemblerPipeline<?, ?, ?> pipeline = pipelineManager.getPipeline(path);
			Outcome<String> outcome = pipeline.disassemble(path);
			if (outcome.isSuccess()) {
				String text = outcome.requireValue();
				if (text != null && text.length() > MAX_SOURCE_CHARS)
					text = text.substring(0, MAX_SOURCE_CHARS) + "\n// ... truncated by Recaf MCP ...";
				return new AssemblyView(info, text, "");
			}
			return new AssemblyView(info, null, formatDiagnostics(outcome.diagnostics()));
		} catch (Throwable t) {
			logger.error("Disassembly of '{}' failed", info.getName(), t);
			return new AssemblyView(info, null, t.toString());
		}
	}

	// ================================================================== mutations

	/**
	 * Compiles the given source and writes the result into the workspace.
	 *
	 * @param name
	 * 		Class name.
	 * @param source
	 * 		Full Java source of the class.
	 *
	 * @return Result containing a diff of the change, or compiler diagnostics on failure.
	 */
	@Nonnull
	public McpToolResult applySource(@Nonnull String name, @Nonnull String source) {
		ClassPathNode path = resolve(name);
		if (path == null)
			return McpToolResult.fail("Class not found in workspace: " + name);
		if (!path.getValue().isJvmClass())
			return McpToolResult.fail("'" + name + "' is an Android/DEX class. Java source recompilation is only " +
					"supported for JVM classes. Use 'get_assembly' + 'apply_assembly' for this class instead.");
		if (!JavacCompiler.isAvailable())
			return McpToolResult.fail("No 'javac' compiler available in this runtime. Use 'replace_bytes' instead.");

		JvmClassInfo info = path.getValue().asJvmClass();
		Workspace workspace = path.getValueOfType(Workspace.class);
		JvmClassBundle bundle = path.getValueOfType(JvmClassBundle.class);
		if (workspace == null || bundle == null)
			return McpToolResult.fail("Incomplete workspace path for class: " + name);

		// Keep the prior decompilation so we can show a diff.
		String before = null;
		try {
			DecompileResult prior = decompilerManager.decompile(workspace, info)
					.get(30, TimeUnit.SECONDS);
			before = prior.getText();
		} catch (Exception ignored) {
			// Not fatal, the diff will just be against an empty document.
		}

		// Compile.
		CompilerResult result;
		try {
			int versionTarget = javacConfig.getDefaultTargetVersion().getValue();
			if (versionTarget < 0)
				versionTarget = JavaVersion.adaptFromClassFileVersion(info.getVersion());
			JavacArgumentsBuilder builder = new JavacArgumentsBuilder()
					.withVersionTarget(versionTarget)
					.withDownsampleTarget(javacConfig.getDefaultDownsampleTargetVersion().getValue())
					.withDebugVariables(javacConfig.getDefaultEmitDebug().getValue())
					.withDebugSourceName(javacConfig.getDefaultEmitDebug().getValue())
					.withDebugLineNumbers(javacConfig.getDefaultEmitDebug().getValue())
					.withClassSource(source)
					.withClassName(info.getName());
			result = javac.compile(builder.build(), workspace, null);
		} catch (Throwable t) {
			logger.error("Compilation of '{}' failed", info.getName(), t);
			return McpToolResult.fail("Compilation threw an exception: " + t);
		}

		if (!result.wasSuccess()) {
			StringBuilder sb = new StringBuilder("Compilation failed:\n");
			if (result.getException() != null)
				sb.append("  ").append(result.getException()).append('\n');
			for (CompilerDiagnostic diagnostic : result.getDiagnostics())
				sb.append("  ").append(diagnostic.toString()).append('\n');
			return McpToolResult.fail(sb.toString().stripTrailing());
		}

		CompileMap compilations = result.getCompilations();
		if (!compilations.containsKey(info.getName()))
			return McpToolResult.fail("Compilation did not produce the original class name. " +
					"Renaming classes is not supported through this tool, use mapping operations instead.");

		// Build updated class models and commit on the FX thread so the UI can react.
		List<JvmClassInfo> updated = new ArrayList<>(compilations.size());
		Map<String, byte[]> compiled = new LinkedHashMap<>(compilations);
		compiled.forEach((className, bytecode) -> {
			JvmClassInfo existing = bundle.get(className);
			JvmClassInfo newInfo = existing != null
					? existing.toJvmClassBuilder().adaptFrom(bytecode).build()
					: new JvmClassInfoBuilder(bytecode).build();
			updated.add(newInfo);
		});

		boolean committed = runOnFxAndWait(() -> saveService.commit(workspace, bundle, updated, List.of()));
		if (!committed)
			return McpToolResult.fail("Failed to apply changes on the UI thread.");

		McpDiff.Result diff = McpDiff.diff(before == null ? "" : before, source);
		String summary = "Modified " + info.getName() + " (+" + diff.added() + "/-" + diff.removed() + ")";
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("status", "ok");
		response.put("summary", summary);
		response.put("class", info.getName());
		response.put("innerClasses", Math.max(0, compilations.size() - 1));
		response.put("addedLines", diff.added());
		response.put("removedLines", diff.removed());
		response.put("diff", diff.text());
		return McpToolResult.ok(GSON.toJson(response));
	}

	/**
	 * Assembles the given assembler text <i>(JVM bytecode or Dalvik, whichever matches the class)</i>
	 * and writes the result into the workspace.
	 * <p>
	 * No external compiler is involved, so this works for Android/DEX classes too.
	 *
	 * @param name
	 * 		Class name.
	 * @param text
	 * 		Full assembler text for the class.
	 *
	 * @return Result containing a diff of the change, or assembler diagnostics on failure.
	 */
	@Nonnull
	public McpToolResult applyAssembly(@Nonnull String name, @Nonnull String text) {
		ClassPathNode path = resolve(name);
		if (path == null)
			return McpToolResult.fail("Class not found in workspace: " + name);

		ClassInfo info = path.getValue();
		Workspace workspace = path.getValueOfType(Workspace.class);
		Object bundle = path.getValueOfType(Bundle.class);
		if (workspace == null || bundle == null)
			return McpToolResult.fail("Incomplete workspace path for class: " + name);

		// Snapshot the previous text so we can show a diff.
		String before = "";
		try {
			Outcome<String> prior = pipelineManager.getPipeline(path).disassemble(path);
			if (prior.isSuccess())
				before = prior.requireValue();
		} catch (Throwable ignored) {
			// Not fatal, the diff will just be against an empty document.
		}

		ClassInfo assembled;
		try {
			AssemblerPipeline<?, ?, ?> pipeline = pipelineManager.getPipeline(path);
			Outcome<List<Token>> tokenized = pipeline.tokenize(text, info.getName());
			if (!tokenized.isSuccess())
				return McpToolResult.fail("Assembler tokenization failed:\n" + formatDiagnostics(tokenized.diagnostics()));

			Outcome<List<ASTElement>> parsed = pipeline.fullParse(tokenized.requireValue());
			if (!parsed.isSuccess())
				return McpToolResult.fail("Assembler parse failed:\n" + formatDiagnostics(parsed.diagnostics()));

			Outcome<? extends ClassResult<?>> outcome = pipeline.assemble(parsed.requireValue(), path);
			if (!outcome.isSuccess())
				return McpToolResult.fail("Assembler failed:\n" + formatDiagnostics(outcome.diagnostics()));

			ClassResult<?> result = outcome.requireValue();
			assembled = classInfoFromRepresentation(pipeline, result.representation());
			if (assembled == null)
				return McpToolResult.fail("Assembler produced no class representation.");
		} catch (Throwable t) {
			logger.error("Assembly of '{}' failed", info.getName(), t);
			return McpToolResult.fail("Assembly threw an exception: " + t);
		}

		if (!assembled.getName().equals(info.getName()))
			return McpToolResult.fail("Renaming classes through the assembler is not supported. " +
					"Expected '" + info.getName() + "' but got '" + assembled.getName() + "'.");

		boolean committed = commitOnFx(workspace, bundle, assembled);
		if (!committed)
			return McpToolResult.fail("Failed to apply changes on the UI thread.");

		McpDiff.Result diff = McpDiff.diff(before, text);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("status", "ok");
		response.put("summary", "Assembled " + info.getName() + " (+" + diff.added() + "/-" + diff.removed() + ")");
		response.put("class", info.getName());
		response.put("kind", info.isJvmClass() ? "jvm" : "android");
		response.put("addedLines", diff.added());
		response.put("removedLines", diff.removed());
		response.put("diff", diff.text());
		return McpToolResult.ok(GSON.toJson(response));
	}

	/**
	 * Replaces the raw bytecode of a class, without any compilation step.
	 *
	 * @param name
	 * 		Class name.
	 * @param base64
	 * 		Base64 encoded class file bytes.
	 *
	 * @return Result containing a diff of the decompiled before/after state.
	 */
	@Nonnull
	public McpToolResult replaceBytes(@Nonnull String name, @Nonnull String base64) {
		byte[] bytecode;
		try {
			bytecode = Base64.getDecoder().decode(base64.trim());
		} catch (IllegalArgumentException ex) {
			return McpToolResult.fail("Invalid base64 payload.");
		}

		ClassPathNode path = resolve(name);
		if (path == null)
			return McpToolResult.fail("Class not found in workspace: " + name);
		if (!path.getValue().isJvmClass())
			return McpToolResult.fail("'" + name + "' is an Android/DEX class. Raw JVM bytecode replacement is not " +
					"applicable. Use 'get_assembly' + 'apply_assembly' for this class instead.");

		JvmClassInfo info = path.getValue().asJvmClass();
		Workspace workspace = path.getValueOfType(Workspace.class);
		JvmClassBundle bundle = path.getValueOfType(JvmClassBundle.class);
		if (workspace == null || bundle == null)
			return McpToolResult.fail("Incomplete workspace path for class: " + name);

		// Sanity check the bytecode before we put it in the workspace.
		String newName;
		try {
			newName = new ClassReader(bytecode).getClassName();
		} catch (Throwable t) {
			return McpToolResult.fail("Payload is not a valid class file: " + t);
		}
		if (!newName.equals(info.getName()))
			return McpToolResult.fail("Payload declares class '" + newName + "' but '" + info.getName() + "' was expected.");

		String before = null;
		try {
			before = decompilerManager.decompile(workspace, info).get(30, TimeUnit.SECONDS).getText();
		} catch (Exception ignored) {
			// Ignore.
		}

		JvmClassInfo replacement = info.toJvmClassBuilder().adaptFrom(bytecode).build();
		boolean committed = runOnFxAndWait(() -> saveService.commit(workspace, bundle, replacement));
		if (!committed)
			return McpToolResult.fail("Failed to apply changes on the UI thread.");

		String after = null;
		try {
			after = decompilerManager.decompile(workspace, replacement).get(30, TimeUnit.SECONDS).getText();
		} catch (Exception ignored) {
			// Ignore.
		}

		McpDiff.Result diff = McpDiff.diff(before == null ? "" : before, after == null ? "" : after);
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("status", "ok");
		response.put("class", info.getName());
		response.put("size", bytecode.length);
		response.put("addedLines", diff.added());
		response.put("removedLines", diff.removed());
		response.put("diff", diff.text());
		return McpToolResult.ok(GSON.toJson(response));
	}

	// ================================================================== helpers

	/**
	 * @param name
	 * 		Class name in either dotted or internal form.
	 *
	 * @return Path to the class, or {@code null} if it is not in the workspace.
	 */
	@Nullable
	public ClassPathNode resolve(@Nonnull String name) {
		String normalized = normalize(name);
		Workspace workspace = workspaceManager.getCurrent();
		ClassPathNode path = workspace.findJvmClass(normalized);
		if (path == null)
			path = workspace.findAndroidClass(normalized);
		if (path == null)
			path = workspace.findJvmClass(name.trim());
		if (path == null)
			path = workspace.findAndroidClass(name.trim());
		return path;
	}

	/**
	 * @param name
	 * 		Class name in either dotted or internal form.
	 *
	 * @return Normalized internal class name.
	 */
	@Nonnull
	public static String normalize(@Nonnull String name) {
		String normalized = name.trim();
		if (normalized.startsWith("L") && normalized.endsWith(";"))
			normalized = normalized.substring(1, normalized.length() - 1);
		if (normalized.endsWith(".class"))
			normalized = normalized.substring(0, normalized.length() - 6);
		return normalized.replace('.', '/');
	}

	/**
	 * @param runnable
	 * 		Action to run on the FX thread.
	 *
	 * @return {@code true} when the action completed without throwing.
	 */
	private boolean runOnFxAndWait(@Nonnull Runnable runnable) {
		return McpFx.runAndWait(runnable);
	}

	/**
	 * Commits a class into the workspace bundle on the UI thread.
	 *
	 * @param workspace
	 * 		Containing workspace.
	 * @param bundle
	 * 		Bundle to write into.
	 * @param info
	 * 		Class to write.
	 *
	 * @return {@code true} when the write succeeded.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private boolean commitOnFx(@Nonnull Workspace workspace, @Nonnull Object bundle, @Nonnull ClassInfo info) {
		return runOnFxAndWait(() -> saveService.commit(workspace, (Bundle) bundle, info));
	}

	/**
	 * Converts an assembler representation into Recaf's class model.
	 *
	 * @param pipeline
	 * 		Pipeline that produced the representation.
	 * @param representation
	 * 		Assembler output representation.
	 *
	 * @return Class info, or {@code null} when the representation type is unknown.
	 */
	@Nullable
	private static ClassInfo classInfoFromRepresentation(@Nonnull AssemblerPipeline<?, ?, ?> pipeline,
	                                                    @Nullable Object representation) {
		if (representation == null)
			return null;
		try {
			return pipeline.getClassInfo(Unchecked.cast(representation));
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * @param diagnostics
	 * 		Assembler diagnostics.
	 *
	 * @return One line per diagnostic.
	 */
	@Nonnull
	private static String formatDiagnostics(@Nonnull Collection<? extends Diagnostic> diagnostics) {
		if (diagnostics.isEmpty())
			return "  (no diagnostics provided)";
		return diagnostics.stream()
				.map(diagnostic -> "  " + diagnostic)
				.collect(Collectors.joining("\n"));
	}

	@Nonnull
	private static Map<String, Object> classSummary(@Nonnull ClassInfo info) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", info.getName());
		map.put("package", info.getPackageName());
		map.put("fields", info.getFields().size());
		map.put("methods", info.getMethods().size());
		if (info.isJvmClass())
			map.put("version", info.asJvmClass().getVersion());
		return map;
	}

	@Nonnull
	private static Map<String, Object> memberEntry(@Nonnull ClassInfo owner, @Nonnull String kind,
	                                               @Nonnull String name, @Nonnull String descriptor) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("class", owner.getName());
		map.put("kind", kind);
		map.put("name", name);
		map.put("descriptor", descriptor);
		return map;
	}

	@Nonnull
	private static String describeResource(@Nullable WorkspaceResource resource) {
		if (resource == null)
			return "";
		try {
			int classes = resource.getJvmClassBundle().size();
			int android = resource.getAndroidClassBundles().values().stream()
					.mapToInt(bundle -> bundle.size()).sum();
			int files = resource.getFileBundle().size();
			return resource.getClass().getSimpleName()
					+ " (classes=" + classes + ", androidClasses=" + android + ", files=" + files + ")";
		} catch (Throwable t) {
			return resource.getClass().getSimpleName();
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	/**
	 * Decompilation outcome for a single class.
	 *
	 * @param info
	 * 		Class that was decompiled.
	 * @param source
	 * 		Decompiled source, or {@code null} on failure.
	 * @param error
	 * 		Failure cause, or {@code null} on success.
	 */
	public record SourceView(@Nonnull ClassInfo info, @Nullable String source, @Nullable Throwable error) {
		/**
		 * @return {@code true} when the source was produced.
		 */
		public boolean isSuccess() {
			return source != null;
		}

		/**
		 * @return {@code true} when the class is a JVM class and not a DEX class.
		 */
		public boolean isJvm() {
			return info.isJvmClass();
		}
	}

	/**
	 * Disassembly outcome for a single class.
	 *
	 * @param info
	 * 		Class that was disassembled.
	 * @param text
	 * 		Assembler text, or {@code null} on failure.
	 * @param error
	 * 		Diagnostics describing the failure, empty on success.
	 */
	public record AssemblyView(@Nonnull ClassInfo info, @Nullable String text, @Nonnull String error) {
		/**
		 * @return {@code true} when the assembler text was produced.
		 */
		public boolean isSuccess() {
			return text != null;
		}

		/**
		 * @return {@code true} when the class is a JVM class and not a DEX class.
		 */
		public boolean isJvm() {
			return info.isJvmClass();
		}
	}
}
