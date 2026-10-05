import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * StoreOps harness: deterministic hard gates, verdict computation and routing.
 *
 * <p>The only LLM-variable input is the Evaluator's PASS / FAIL / NA answer (with evidence) for each
 * checklist item. Everything else — tool-output parsing, git checks, scoring, verdict and next action
 * — is computed here, so the same inputs always produce the same verdict.
 *
 * <pre>
 *   java .harness/tools/HarnessGates.java gates    --sprint N --base SHA [--contract F] [--skip-build]
 *   java .harness/tools/HarnessGates.java verdict  --sprint N --iteration I
 *   java .harness/tools/HarnessGates.java route    --sprint N --iteration I [--max 3] [--evaluator-retried]
 *   java .harness/tools/HarnessGates.java selftest
 * </pre>
 *
 * Run from the repository root. No dependencies beyond the JDK 17 that builds StoreOps.
 */
public final class HarnessGates {

    // ------------------------------------------------------------------ registry
    // Must mirror .harness/skills/grading-criteria/SKILL.md (GC-1, GC-2); `selftest` verifies this.

    record Check(String id, String dim, boolean blocking, boolean naAllowed) { }

    static final Map<String, Integer> WEIGHTS = new LinkedHashMap<>();

    static {
        WEIGHTS.put("D1", 35);
        WEIGHTS.put("D2", 30);
        WEIGHTS.put("D3", 25);
        WEIGHTS.put("D4", 10);
    }

    static final List<Check> CHECKS = List.of(
            new Check("A1", "D1", true, true), new Check("A2", "D1", true, true),
            new Check("A3", "D1", true, true), new Check("A4", "D1", false, true),
            new Check("A5", "D1", false, true), new Check("A6", "D1", true, false),
            new Check("C1", "D2", true, false), new Check("C2", "D2", true, true),
            new Check("C3", "D2", true, true), new Check("C4", "D2", true, false),
            new Check("C5", "D2", true, false), new Check("C6", "D2", false, true),
            new Check("C7", "D2", false, true),
            new Check("T1", "D3", true, false), new Check("T2", "D3", false, true),
            new Check("T3", "D3", false, true), new Check("T4", "D3", false, true),
            new Check("T5", "D3", false, false),
            new Check("Q1", "D4", false, false), new Check("Q2", "D4", false, true),
            new Check("Q3", "D4", false, false), new Check("Q4", "D4", false, false));

    static final List<String> LOG_GATES = List.of(
            "HG-BUILD", "HG-COMPILE", "HG-CHECKSTYLE", "HG-SPOTBUGS", "HG-ARCH", "HG-TESTS", "HG-COVERAGE");
    static final List<String> GIT_GATES = List.of("HG-PROTECTED", "HG-SUPPRESS", "HG-SCOPE", "HG-AC-TRACE");

    /** Protected paths (CLAUDE.md §5.2 O2). The Generator may never change these. */
    static final List<Pattern> PROTECTED = List.of(
            Pattern.compile("^CLAUDE\\.md$"),
            Pattern.compile("^\\.harness/(agents|skills|tools)/"),
            Pattern.compile("^config/"),
            Pattern.compile("^pom\\.xml$"),
            Pattern.compile("^src/app/src/test/java/com/storeops/architecture/"));

    /** Gate-silencing constructs (CLAUDE.md §5.2 O3), checked in added lines under src/. */
    static final Pattern SUPPRESS =
            Pattern.compile("@SuppressWarnings|@SuppressFBWarnings|CHECKSTYLE:OFF|@Disabled|<skip>true</skip>");

    static final int PASS_THRESHOLD = 85;
    static final int CONDITIONAL_THRESHOLD = 70;
    static final Path OUT = Paths.get(".harness", "output");

    // ------------------------------------------------------------------ data

    record Gate(String id, String result, String evidence) {
        boolean passed() {
            return "PASS".equals(result);
        }
    }

    record Contract(List<String> allowed, List<String> acIds) { }

    record CheckResult(String id, String result, String evidence, String note) { }

    record Verdict(String verdict, int score, List<String> failedGates, Map<String, Double> dimScores,
            List<String> blockingFailed, List<String> nonBlockingFailed, List<CheckResult> checks,
            boolean ambiguous) { }

    record Route(String effectiveVerdict, String nextAction, int score, List<String> overrides) { }

    // ------------------------------------------------------------------ entry point

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        Map<String, String> opts = parseOpts(args);
        int code = switch (args[0]) {
            case "gates" -> cmdGates(opts);
            case "verdict" -> cmdVerdict(opts);
            case "route" -> cmdRoute(opts);
            case "selftest" -> cmdSelftest();
            default -> {
                usage();
                yield 2;
            }
        };
        System.exit(code);
    }

    static void usage() {
        System.out.println("usage: java .harness/tools/HarnessGates.java gates|verdict|route|selftest [options]");
    }

    static Map<String, String> parseOpts(String[] args) {
        Map<String, String> opts = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                boolean hasValue = i + 1 < args.length && !args[i + 1].startsWith("--");
                opts.put(key, hasValue ? args[++i] : "true");
            }
        }
        return opts;
    }

    static String req(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return v;
    }

    static Path out(String sprint, String suffix) {
        return OUT.resolve("sprint-" + sprint + "-" + suffix);
    }

    // ------------------------------------------------------------------ gates

    static int cmdGates(Map<String, String> opts) throws Exception {
        String sprint = req(opts, "sprint");
        String base = opts.getOrDefault("base", "HEAD");
        Path log = out(sprint, "checks.log");
        Path contractPath = Paths.get(opts.getOrDefault("contract", out(sprint, "contract.md").toString()));
        if (!opts.containsKey("skip-build")) {
            System.out.println("Running gate command (./mvnw -B clean test) ...");
            runBuild(log);
        }
        String logText = Files.exists(log) ? readLenient(log) : "";
        List<Gate> gates = new ArrayList<>(evaluateLog(logText));

        Contract contract = Files.exists(contractPath) ? parseContract(readLenient(contractPath)) : null;
        Set<String> untracked = lines(git("ls-files", "--others", "--exclude-standard"));
        Set<String> sprintChanged = union(lines(git("diff", "--name-only", "HEAD")), untracked);
        sprintChanged.removeIf(p -> p.startsWith(".harness/output/"));
        Set<String> cumulative = union(lines(git("diff", "--name-only", base)), untracked);

        gates.add(protectedGate(cumulative));
        gates.add(suppressGate(addedSourceLines(git("diff", "HEAD", "-U0", "--no-color", "--no-ext-diff"),
                untracked)));
        gates.add(scopeGate(sprintChanged, contract, contractPath));
        gates.add(acTraceGate(contract, contractPath, collectAcTags(Paths.get("src"))));

        String doc = renderGates(sprint, base, exitCode(logText), gates);
        write(out(sprint, "gates.md"), doc);
        System.out.print(doc);
        return gates.stream().allMatch(Gate::passed) ? 0 : 1;
    }

    static void runBuild(Path log) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        // Absolute wrapper path: Windows may have NoDefaultCurrentDirectoryInExePath set.
        String wrapper = Paths.get(windows ? "mvnw.cmd" : "mvnw").toAbsolutePath().toString();
        List<String> cmd = windows
                ? List.of("cmd", "/c", wrapper, "-B", "clean", "test")
                : List.of(wrapper, "-B", "clean", "test");
        Files.createDirectories(log.getParent());
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done = p.waitFor(15, TimeUnit.MINUTES);
        if (!done) {
            p.destroyForcibly();
        }
        int code = done ? p.exitValue() : -1;
        Files.writeString(log, "\nGATE_EXIT_CODE=" + code + "\n", StandardOpenOption.APPEND);
    }

    static final Pattern EXIT = Pattern.compile("GATE_EXIT_CODE=(-?\\d+)");
    static final Pattern REACTOR = Pattern.compile("^\\[INFO\\] (.+?) \\.+\\s*(SUCCESS|FAILURE|SKIPPED)");
    static final Pattern CHECKSTYLE_COUNT = Pattern.compile("You have (\\d+) Checkstyle violation");
    static final Pattern SPOTBUGS_COUNT = Pattern.compile("BugInstance size is (\\d+)");
    static final Pattern TESTS_LINE =
            Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+).*? in (\\S+)");

    static Integer exitCode(String log) {
        Matcher m = EXIT.matcher(log);
        Integer code = null;
        while (m.find()) {
            code = Integer.valueOf(m.group(1));
        }
        return code;
    }

    /** Derives the seven log-based gates from the gate command output. Pure function. */
    static List<Gate> evaluateLog(String log) {
        Integer exit = exitCode(log);
        if (exit == null) {
            return LOG_GATES.stream().map(g -> new Gate(g, "NOT_RUN", "GATE_EXIT_CODE missing from checks log"))
                    .collect(Collectors.toList());
        }
        List<String> lines = log.lines().map(String::strip).collect(Collectors.toList());
        Map<String, Gate> gates = new LinkedHashMap<>();
        gates.put("HG-BUILD", new Gate("HG-BUILD", exit == 0 ? "PASS" : "FAIL", "GATE_EXIT_CODE=" + exit));

        gates.put("HG-COMPILE", firstMatch(lines, "HG-COMPILE",
                l -> l.contains("COMPILATION ERROR")
                        || l.contains("Failed to execute goal") && l.contains("maven-compiler-plugin")));
        gates.put("HG-CHECKSTYLE", firstMatch(lines, "HG-CHECKSTYLE",
                l -> countAbove(CHECKSTYLE_COUNT, l)
                        || l.contains("Failed to execute goal") && l.contains("maven-checkstyle-plugin")));
        gates.put("HG-SPOTBUGS", firstMatch(lines, "HG-SPOTBUGS",
                l -> countAbove(SPOTBUGS_COUNT, l)
                        || l.contains("Failed to execute goal") && l.contains("spotbugs-maven-plugin")));
        gates.put("HG-ARCH", firstMatch(lines, "HG-ARCH",
                l -> l.contains("Architecture Violation")
                        || l.contains("ArchitectureRulesTest") && (l.contains("<<< FAIL") || l.contains("<<< ERROR"))
                        || testFailureIn(l, true)));
        gates.put("HG-TESTS", firstMatch(lines, "HG-TESTS", l -> testFailureIn(l, false)));
        gates.put("HG-COVERAGE", firstMatch(lines, "HG-COVERAGE",
                l -> l.contains("Rule violated for") || l.contains("Coverage checks have not been met")));
        for (String id : List.of("HG-COMPILE", "HG-CHECKSTYLE")) {
            gates.put(id, withSourceLocation(gates.get(id), lines));
        }

        // A gate is only proven green if every module actually completed.
        List<String> incomplete = new ArrayList<>();
        boolean sawReactor = false;
        for (String l : lines) {
            Matcher m = REACTOR.matcher(l);
            if (m.find()) {
                sawReactor = true;
                if (!"SUCCESS".equals(m.group(2))) {
                    incomplete.add(m.group(1).trim() + " " + m.group(2));
                }
            }
        }
        if (!sawReactor) {
            incomplete.add("no Reactor Summary in log");
        }
        if (!incomplete.isEmpty()) {
            String why = "build incomplete: " + String.join(", ", incomplete);
            for (String id : LOG_GATES) {
                Gate g = gates.get(id);
                if (!"HG-BUILD".equals(id) && g.passed()) {
                    gates.put(id, new Gate(id, "NOT_RUN", why));
                }
            }
        }
        return new ArrayList<>(gates.values());
    }

    interface LinePredicate {
        boolean test(String line);
    }

    static Gate firstMatch(List<String> lines, String id, LinePredicate failsOn) {
        for (String l : lines) {
            if (failsOn.test(l)) {
                return new Gate(id, "FAIL", l);
            }
        }
        return new Gate(id, "PASS", "no failure pattern in checks log");
    }

    static final Pattern SOURCE_LOCATION = Pattern.compile("^\\[ERROR\\] .*\\.java:\\[?\\d+");

    /** Prefers the first "[ERROR] File.java:line" message as evidence, so findings can cite file:line. */
    static Gate withSourceLocation(Gate g, List<String> lines) {
        if (g.passed()) {
            return g;
        }
        for (String l : lines) {
            if (SOURCE_LOCATION.matcher(l).find()) {
                String root = Paths.get("").toAbsolutePath().toString();
                String rel = l.replace(root + java.io.File.separator, "").replace(root + "/", "");
                return new Gate(g.id(), g.result(), rel);
            }
        }
        return g;
    }

    static boolean countAbove(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.find() && Integer.parseInt(m.group(1)) > 0;
    }

    static boolean testFailureIn(String line, boolean architecture) {
        Matcher m = TESTS_LINE.matcher(line);
        if (!m.find()) {
            return false;
        }
        boolean isArch = m.group(4).endsWith("ArchitectureRulesTest");
        int failed = Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
        return failed > 0 && isArch == architecture;
    }

    static Gate protectedGate(Set<String> cumulative) {
        List<String> hits = cumulative.stream()
                .filter(p -> PROTECTED.stream().anyMatch(rx -> rx.matcher(p).find()))
                .sorted().collect(Collectors.toList());
        return hits.isEmpty()
                ? new Gate("HG-PROTECTED", "PASS", "no protected path changed")
                : new Gate("HG-PROTECTED", "FAIL", "changed: " + String.join(", ", hits));
    }

    /** Returns "path: line" for each added line under src/ (tracked diff + untracked files). */
    static List<String> addedSourceLines(String unifiedDiff, Set<String> untracked) {
        List<String> added = new ArrayList<>();
        String file = null;
        for (String raw : unifiedDiff.split("\n")) {
            String l = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (l.startsWith("+++ ")) {
                file = l.startsWith("+++ b/") ? l.substring(6) : null;
            } else if (l.startsWith("+") && file != null && file.startsWith("src/")) {
                added.add(file + ": " + l.substring(1));
            }
        }
        for (String path : untracked) {
            Path p = Paths.get(path);
            if (path.startsWith("src/") && Files.isRegularFile(p)) {
                readLenient(p).lines().forEach(l -> added.add(path + ": " + l));
            }
        }
        return added;
    }

    static Gate suppressGate(List<String> addedLines) {
        List<String> hits = addedLines.stream().filter(l -> SUPPRESS.matcher(l).find())
                .collect(Collectors.toList());
        return hits.isEmpty()
                ? new Gate("HG-SUPPRESS", "PASS", "no suppression in added lines")
                : new Gate("HG-SUPPRESS", "FAIL", hits.size() + " hit(s), first: " + hits.get(0).strip());
    }

    static Gate scopeGate(Set<String> changed, Contract contract, Path contractPath) {
        if (contract == null) {
            return new Gate("HG-SCOPE", "FAIL", "contract not found: " + slash(contractPath));
        }
        if (contract.allowed().isEmpty()) {
            return new Gate("HG-SCOPE", "FAIL", "contract has no 'Allowed paths' row");
        }
        List<Pattern> globs = contract.allowed().stream().map(HarnessGates::glob).collect(Collectors.toList());
        List<String> outside = changed.stream()
                .filter(p -> globs.stream().noneMatch(g -> g.matcher(p).matches()))
                .sorted().collect(Collectors.toList());
        return outside.isEmpty()
                ? new Gate("HG-SCOPE", "PASS", changed.size() + " changed path(s), all within allowed paths")
                : new Gate("HG-SCOPE", "FAIL", "outside allowed paths: " + String.join(", ", outside));
    }

    static final Pattern AC_TAG = Pattern.compile("@DisplayName\\(\\s*\"(AC-\\d+\\.\\d+)(?![\\d.])");

    static Set<String> collectAcTags(Path srcRoot) throws IOException {
        Set<String> tags = new TreeSet<>();
        if (!Files.isDirectory(srcRoot)) {
            return tags;
        }
        try (Stream<Path> files = Files.walk(srcRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> slash(p).contains("/src/test/") && p.toString().endsWith(".java"))
                    .forEach(p -> {
                        Matcher m = AC_TAG.matcher(readLenient(p));
                        while (m.find()) {
                            tags.add(m.group(1));
                        }
                    });
        }
        return tags;
    }

    static Gate acTraceGate(Contract contract, Path contractPath, Set<String> tags) {
        if (contract == null) {
            return new Gate("HG-AC-TRACE", "FAIL", "contract not found: " + slash(contractPath));
        }
        if (contract.acIds().isEmpty()) {
            return new Gate("HG-AC-TRACE", "FAIL", "contract lists no acceptance criteria");
        }
        List<String> missing = contract.acIds().stream().filter(id -> !tags.contains(id)).collect(Collectors.toList());
        return missing.isEmpty()
                ? new Gate("HG-AC-TRACE", "PASS", "all " + contract.acIds().size() + " AC(s) have a tagged test")
                : new Gate("HG-AC-TRACE", "FAIL", "no @DisplayName test for: " + String.join(", ", missing));
    }

    static final Pattern ALLOWED_ROW = Pattern.compile("(?m)^\\|\\s*Allowed paths\\s*\\|(.*)\\|\\s*$");
    static final Pattern AC_HEADING = Pattern.compile("(?m)^#{2,4}\\s*(AC-\\d+\\.\\d+)\\b");

    static Contract parseContract(String text) {
        List<String> allowed = new ArrayList<>();
        Matcher a = ALLOWED_ROW.matcher(text);
        if (a.find()) {
            for (String part : a.group(1).split(",")) {
                String g = part.replace("`", "").trim();
                if (!g.isEmpty() && !g.equals("…") && !g.equals("...")) {
                    allowed.add(g);
                }
            }
        }
        Set<String> acs = new LinkedHashSet<>();
        Matcher m = AC_HEADING.matcher(text);
        while (m.find()) {
            acs.add(m.group(1));
        }
        return new Contract(allowed, new ArrayList<>(acs));
    }

    /** Glob with '**' (any depth), '*' (within a segment) and '?' over '/'-separated paths. */
    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < g.length(); i++) {
            char ch = g.charAt(i);
            if (ch == '*') {
                if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                    sb.append(".*");
                    i++;
                } else {
                    sb.append("[^/]*");
                }
            } else if (ch == '?') {
                sb.append("[^/]");
            } else {
                sb.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(sb.append('$').toString());
    }

    static String renderGates(String sprint, String base, Integer exit, List<Gate> gates) {
        List<String> failed = gates.stream().filter(g -> !g.passed()).map(Gate::id).collect(Collectors.toList());
        StringBuilder sb = new StringBuilder();
        sb.append("---\n")
                .append("sprint: ").append(sprint).append('\n')
                .append("base: ").append(base).append('\n')
                .append("gate_command_exit_code: ").append(exit == null ? "none" : exit).append('\n')
                .append("hard_gates_failed: [").append(String.join(", ", failed)).append("]\n")
                .append("---\n")
                .append("# Sprint ").append(sprint)
                .append(" Hard Gates (generated by .harness/tools/HarnessGates.java - do not edit)\n\n")
                .append("| Gate | Result | Evidence |\n|---|---|---|\n");
        for (Gate g : gates) {
            sb.append("| ").append(g.id()).append(" | ").append(g.result()).append(" | ")
                    .append(cell(g.evidence())).append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ verdict

    static int cmdVerdict(Map<String, String> opts) {
        String sprint = req(opts, "sprint");
        String iteration = req(opts, "iteration");
        Map<String, String> gates = readGates(out(sprint, "gates.md"));
        Path feedback = out(sprint, "evaluator-feedback.md");
        Map<String, String[]> rows = Files.exists(feedback) ? parseChecklist(readLenient(feedback)) : Map.of();
        Verdict v = computeVerdict(gates, rows);
        String doc = renderVerdict(sprint, iteration, gates, v);
        write(out(sprint, "verdict.md"), doc);
        System.out.print(doc);
        return 0;
    }

    static Map<String, String> readGates(Path gatesFile) {
        Map<String, String> results = new LinkedHashMap<>();
        if (!Files.exists(gatesFile)) {
            return results;
        }
        for (String l : readLenient(gatesFile).lines().collect(Collectors.toList())) {
            String[] c = cells(l);
            if (c.length >= 2 && c[0].startsWith("HG-")) {
                results.put(c[0], c[1]);
            }
        }
        return results;
    }

    /** Collects checklist rows: any table row whose first cell is a registered check id. */
    static Map<String, String[]> parseChecklist(String text) {
        Set<String> ids = CHECKS.stream().map(Check::id).collect(Collectors.toSet());
        Map<String, String[]> rows = new LinkedHashMap<>();
        for (String l : text.lines().collect(Collectors.toList())) {
            String[] c = cells(l);
            if (c.length >= 2 && ids.contains(c[0])) {
                String result = c[1].toUpperCase(Locale.ROOT).replace("/", "").replace(" ", "");
                String evidence = c.length >= 3 ? c[c.length - 1] : "";
                String[] prev = rows.get(c[0]);
                if (prev != null && !prev[0].equals(result)) {
                    rows.put(c[0], new String[] {"CONFLICT", evidence});
                } else {
                    rows.put(c[0], new String[] {result, evidence});
                }
            }
        }
        return rows;
    }

    /** The verdict rule of grading-criteria GC-3. Pure function of (gate results, checklist rows). */
    static Verdict computeVerdict(Map<String, String> gates, Map<String, String[]> rows) {
        List<String> failedGates = new ArrayList<>();
        for (String id : concat(LOG_GATES, GIT_GATES)) {
            if (!"PASS".equals(gates.get(id))) {
                failedGates.add(id);
            }
        }
        boolean ambiguous = rows.isEmpty();
        List<CheckResult> results = new ArrayList<>();
        for (Check c : CHECKS) {
            String[] row = rows.get(c.id());
            results.add(normalise(c, row));
        }
        Map<String, Double> dimScores = new LinkedHashMap<>();
        double total = 0;
        for (Map.Entry<String, Integer> w : WEIGHTS.entrySet()) {
            List<CheckResult> inDim = new ArrayList<>();
            for (int i = 0; i < CHECKS.size(); i++) {
                if (CHECKS.get(i).dim().equals(w.getKey()) && !"NA".equals(results.get(i).result())) {
                    inDim.add(results.get(i));
                }
            }
            long passed = inDim.stream().filter(r -> "PASS".equals(r.result())).count();
            double s = inDim.isEmpty() ? w.getValue() : w.getValue() * (double) passed / inDim.size();
            dimScores.put(w.getKey(), s);
            total += s;
        }
        int score = (int) Math.floor(total + 1e-9);
        List<String> blockingFailed = new ArrayList<>();
        List<String> nonBlockingFailed = new ArrayList<>();
        for (int i = 0; i < CHECKS.size(); i++) {
            if ("FAIL".equals(results.get(i).result())) {
                (CHECKS.get(i).blocking() ? blockingFailed : nonBlockingFailed).add(CHECKS.get(i).id());
            }
        }
        String verdict;
        if (!failedGates.isEmpty() || !blockingFailed.isEmpty()) {
            verdict = "FAIL";
        } else if (score >= PASS_THRESHOLD) {
            verdict = "PASS";
        } else if (score >= CONDITIONAL_THRESHOLD) {
            verdict = "CONDITIONAL_PASS";
        } else {
            verdict = "FAIL";
        }
        return new Verdict(verdict, score, failedGates, dimScores, blockingFailed, nonBlockingFailed, results,
                ambiguous);
    }

    /** Ambiguity fallback (GC-5): anything other than an evidenced PASS or a permitted NA is a FAIL. */
    static CheckResult normalise(Check c, String[] row) {
        if (row == null) {
            return new CheckResult(c.id(), "FAIL", "", "NO_EVIDENCE: row missing");
        }
        String result = row[0];
        String evidence = row[1] == null ? "" : row[1].trim();
        switch (result) {
            case "PASS":
                return evidence.isEmpty() || evidence.equals("-")
                        ? new CheckResult(c.id(), "FAIL", evidence, "NO_EVIDENCE: PASS without evidence")
                        : new CheckResult(c.id(), "PASS", evidence, "");
            case "FAIL":
                return new CheckResult(c.id(), "FAIL", evidence, "");
            case "NA":
                return c.naAllowed()
                        ? new CheckResult(c.id(), "NA", evidence, "")
                        : new CheckResult(c.id(), "FAIL", evidence, "N/A not permitted for this check");
            case "CONFLICT":
                return new CheckResult(c.id(), "FAIL", evidence, "CONFLICTING_ROWS");
            default:
                return new CheckResult(c.id(), "FAIL", evidence, "UNPARSEABLE result '" + result + "'");
        }
    }

    static String renderVerdict(String sprint, String iteration, Map<String, String> gates, Verdict v) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n")
                .append("sprint: ").append(sprint).append('\n')
                .append("iteration: ").append(iteration).append('\n')
                .append("verdict: ").append(v.verdict()).append('\n')
                .append("hard_gates_failed: [").append(String.join(", ", v.failedGates())).append("]\n")
                .append("score: ").append(v.score()).append('\n')
                .append("---\n")
                .append("# Sprint ").append(sprint).append(" Computed Verdict - iteration ").append(iteration)
                .append(" (generated by .harness/tools/HarnessGates.java - do not edit)\n\n")
                .append("| Dimension | Weight | Score |\n|---|---|---|\n");
        for (Map.Entry<String, Double> d : v.dimScores().entrySet()) {
            sb.append("| ").append(d.getKey()).append(" | ").append(WEIGHTS.get(d.getKey())).append("% | ")
                    .append(String.format(Locale.ROOT, "%.2f", d.getValue())).append(" |\n");
        }
        sb.append("| **Total (floor)** | 100% | **").append(v.score()).append("** |\n\n")
                .append("Blocking checks failed: ").append(listOrNone(v.blockingFailed())).append('\n')
                .append("Non-blocking checks failed: ").append(listOrNone(v.nonBlockingFailed())).append('\n')
                .append("Checklist ambiguous (no recognisable rows): ").append(v.ambiguous()).append("\n\n")
                .append("| Check | Normalised result | Note |\n|---|---|---|\n");
        for (CheckResult r : v.checks()) {
            sb.append("| ").append(r.id()).append(" | ").append(r.result()).append(" | ").append(cell(r.note()))
                    .append(" |\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ route

    static int cmdRoute(Map<String, String> opts) throws Exception {
        String sprint = req(opts, "sprint");
        int iteration = Integer.parseInt(req(opts, "iteration"));
        int max = Integer.parseInt(opts.getOrDefault("max", "3"));
        boolean evaluatorRetried = opts.containsKey("evaluator-retried");
        Path gatesFile = out(sprint, "gates.md");
        Path log = out(sprint, "checks.log");
        Path feedback = out(sprint, "evaluator-feedback.md");

        List<String> overrides = new ArrayList<>();
        Map<String, String> gates = readGates(gatesFile);
        Map<String, String> gatesMeta = Files.exists(gatesFile) ? frontMatter(readLenient(gatesFile)) : Map.of();

        // O1: the gate evidence must exist and agree with the raw log.
        Integer logExit = Files.exists(log) ? exitCode(readLenient(log)) : null;
        String recorded = gatesMeta.get("gate_command_exit_code");
        if (gates.isEmpty() || logExit == null || !String.valueOf(logExit).equals(recorded)) {
            overrides.add("O1: gates file/checks log missing or exit codes disagree (log=" + logExit
                    + ", gates=" + recorded + ")");
            gates = new LinkedHashMap<>();
        }
        // O2/O3: re-check tampering at routing time (catches edits made after evaluation).
        String base = gatesMeta.getOrDefault("base", "HEAD");
        Set<String> untracked = lines(git("ls-files", "--others", "--exclude-standard"));
        Gate prot = protectedGate(union(lines(git("diff", "--name-only", base)), untracked));
        Gate supp = suppressGate(addedSourceLines(git("diff", "HEAD", "-U0", "--no-color", "--no-ext-diff"), untracked));
        if (!prot.passed()) {
            overrides.add("O2: " + prot.evidence());
            gates.put("HG-PROTECTED", "FAIL");
        }
        if (!supp.passed()) {
            overrides.add("O3: " + supp.evidence());
            gates.put("HG-SUPPRESS", "FAIL");
        }

        String text = Files.exists(feedback) ? readLenient(feedback) : "";
        Map<String, String[]> rows = parseChecklist(text);
        Verdict v = computeVerdict(gates, rows);
        String header = frontMatter(text).get("verdict");
        if (header == null || !header.trim().equals(v.verdict())) {
            overrides.add("O4/O5: evaluator header verdict '" + header + "' replaced by computed '"
                    + v.verdict() + "'");
        }
        Route r = route(v, iteration, max, evaluatorRetried, overrides);
        String doc = renderRoute(sprint, iteration, r);
        write(out(sprint, "route.md"), doc);
        if (Files.exists(feedback) && !r.overrides().isEmpty()) {
            appendOverrides(feedback, r.overrides());
        }
        System.out.print(doc);
        return 0;
    }

    /** CLAUDE.md §5.3 routing table. Pure function. */
    static Route route(Verdict v, int iteration, int max, boolean evaluatorRetried, List<String> overrides) {
        List<String> notes = new ArrayList<>(overrides);
        if (v.ambiguous() && !evaluatorRetried) {
            notes.add("checklist unparseable - re-spawn Evaluator once (does not consume an iteration)");
            return new Route("UNDECIDED", "REEVALUATE", v.score(), notes);
        }
        if (v.ambiguous()) {
            notes.add("EVALUATOR_OUTPUT_AMBIGUOUS after retry");
        }
        String effective = v.ambiguous() ? "FAIL" : v.verdict();
        String next;
        if ("PASS".equals(effective) || "CONDITIONAL_PASS".equals(effective)) {
            next = "ADVANCE";
        } else if (iteration < max) {
            next = "RETRY_GENERATOR";
        } else {
            next = "ESCALATE";
        }
        return new Route(effective, next, v.score(), notes);
    }

    static String renderRoute(String sprint, int iteration, Route r) {
        return "EFFECTIVE_VERDICT=" + r.effectiveVerdict() + "\n"
                + "NEXT_ACTION=" + r.nextAction() + "\n"
                + "SPRINT=" + sprint + "\n"
                + "ITERATION=" + iteration + "\n"
                + "SCORE=" + r.score() + "\n"
                + "OVERRIDES=" + (r.overrides().isEmpty() ? "none" : String.join(" || ", r.overrides())) + "\n";
    }

    static void appendOverrides(Path feedback, List<String> overrides) {
        String text = readLenient(feedback);
        int idx = text.indexOf("\n## Orchestrator overrides");
        String body = idx >= 0 ? text.substring(0, idx) : text.stripTrailing();
        StringBuilder sb = new StringBuilder(body).append("\n\n## Orchestrator overrides\n");
        overrides.forEach(o -> sb.append("- ").append(o).append('\n'));
        write(feedback, sb.toString());
    }

    // ------------------------------------------------------------------ selftest

    static int failures;

    static int cmdSelftest() throws IOException {
        failures = 0;
        selftestLogParsing();
        selftestScoring();
        selftestRouting();
        selftestContractAndGitGates();
        selftestDeterminism();
        selftestRegistryMatchesSkill();
        System.out.println(failures == 0 ? "SELFTEST PASSED" : "SELFTEST FAILED: " + failures + " assertion(s)");
        return failures == 0 ? 0 : 1;
    }

    static void expect(String name, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  expected=" + expected + " actual=" + actual));
    }

    static String reactor(String... modulesAndStatus) {
        StringBuilder sb = new StringBuilder("[INFO] Reactor Summary for StoreOps :: Parent 0.1.0-SNAPSHOT:\n");
        for (int i = 0; i < modulesAndStatus.length; i += 2) {
            sb.append("[INFO] StoreOps :: ").append(modulesAndStatus[i]).append(" ........ ")
                    .append(modulesAndStatus[i + 1]).append(" [  1.0 s]\n");
        }
        return sb.toString();
    }

    static Map<String, String> gateMap(List<Gate> gates) {
        Map<String, String> m = new LinkedHashMap<>();
        gates.forEach(g -> m.put(g.id(), g.result()));
        return m;
    }

    static void selftestLogParsing() {
        System.out.println("log parsing:");
        String green = "[INFO] You have 0 Checkstyle violations.\n[INFO] BugInstance size is 0\n"
                + "[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.9 s -- in "
                + "com.storeops.architecture.ArchitectureRulesTest\n[INFO] All coverage checks have been met.\n"
                + reactor("Shared", "SUCCESS", "Activities", "SUCCESS", "App", "SUCCESS") + "GATE_EXIT_CODE=0\n";
        expect("green build -> all 7 log gates PASS",
                gateMap(evaluateLog(green)).values().stream().allMatch("PASS"::equals), true);

        String checkstyle = "[INFO] You have 1 Checkstyle violation.\n"
                + "[ERROR] src\\main\\java\\com\\storeops\\activities\\service\\X.java:5: Raw exception throw. [RegexpSingleline]\n"
                + reactor("Shared", "SUCCESS", "Activities", "FAILURE", "App", "SKIPPED") + "GATE_EXIT_CODE=1\n";
        Map<String, String> cs = gateMap(evaluateLog(checkstyle));
        expect("checkstyle violation -> HG-CHECKSTYLE FAIL", cs.get("HG-CHECKSTYLE"), "FAIL");
        expect("checkstyle violation -> HG-BUILD FAIL", cs.get("HG-BUILD"), "FAIL");
        expect("skipped modules -> unproven gates NOT_RUN", cs.get("HG-TESTS") + "/" + cs.get("HG-COVERAGE"),
                "NOT_RUN/NOT_RUN");

        String arch = "[ERROR] Tests run: 9, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 2.9 s <<< FAILURE! -- in "
                + "com.storeops.architecture.ArchitectureRulesTest\n"
                + "[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10 s -- in "
                + "com.storeops.StoreOpsApiIntegrationTest\n"
                + reactor("Shared", "SUCCESS", "App", "FAILURE") + "GATE_EXIT_CODE=1\n";
        Map<String, String> ar = gateMap(evaluateLog(arch));
        expect("ArchUnit failure -> HG-ARCH FAIL", ar.get("HG-ARCH"), "FAIL");
        expect("ArchUnit failure is not counted as HG-TESTS failure", ar.get("HG-TESTS"), "NOT_RUN");

        String tests = "[ERROR] Tests run: 8, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 1 s <<< FAILURE! -- in "
                + "com.storeops.activities.routes.ActivityControllerTest\n"
                + reactor("Activities", "FAILURE") + "GATE_EXIT_CODE=1\n";
        expect("ordinary test failure -> HG-TESTS FAIL", gateMap(evaluateLog(tests)).get("HG-TESTS"), "FAIL");

        String coverage = "[WARNING] Rule violated for package com.storeops.activities.service: lines covered ratio is "
                + "0.72, but expected minimum is 0.80\n" + reactor("Activities", "FAILURE") + "GATE_EXIT_CODE=1\n";
        expect("coverage shortfall -> HG-COVERAGE FAIL", gateMap(evaluateLog(coverage)).get("HG-COVERAGE"), "FAIL");

        String spotbugs = "[INFO] BugInstance size is 2\n" + reactor("Programmes", "FAILURE") + "GATE_EXIT_CODE=1\n";
        expect("spotbugs bugs -> HG-SPOTBUGS FAIL", gateMap(evaluateLog(spotbugs)).get("HG-SPOTBUGS"), "FAIL");

        expect("missing exit code -> every log gate NOT_RUN",
                gateMap(evaluateLog("[INFO] BUILD SUCCESS\n")).values().stream().allMatch("NOT_RUN"::equals), true);
    }

    static Map<String, String> allGatesPass() {
        Map<String, String> g = new LinkedHashMap<>();
        concat(LOG_GATES, GIT_GATES).forEach(id -> g.put(id, "PASS"));
        return g;
    }

    static Map<String, String[]> checklist(Map<String, String> overrides) {
        Map<String, String[]> rows = new LinkedHashMap<>();
        for (Check c : CHECKS) {
            String r = overrides.getOrDefault(c.id(), "PASS");
            rows.put(c.id(), new String[] {r, "Fixture.java:1"});
        }
        return rows;
    }

    static void selftestScoring() {
        System.out.println("scoring (grading-criteria GC-4 and edge cases):");
        Verdict ex1 = computeVerdict(allGatesPass(),
                checklist(Map.of("T5", "FAIL", "Q4", "FAIL", "A2", "NA", "C6", "NA")));
        expect("GC-4 example 1 -> PASS 92", ex1.verdict() + " " + ex1.score(), "PASS 92");

        Verdict ex2 = computeVerdict(allGatesPass(),
                checklist(Map.of("T5", "FAIL", "Q4", "FAIL", "A2", "NA", "C6", "NA", "T1", "FAIL")));
        expect("GC-4 example 2 (status-only test) -> FAIL 87", ex2.verdict() + " " + ex2.score(), "FAIL 87");

        Verdict cond = computeVerdict(allGatesPass(),
                checklist(Map.of("A4", "FAIL", "A5", "FAIL", "C6", "FAIL", "T2", "FAIL")));
        expect("non-blocking failures scoring 79 -> CONDITIONAL_PASS", cond.verdict() + " " + cond.score(),
                "CONDITIONAL_PASS 79");

        Map<String, String> gates = allGatesPass();
        gates.put("HG-COVERAGE", "FAIL");
        expect("perfect checklist but failed hard gate -> FAIL",
                computeVerdict(gates, checklist(Map.of())).verdict(), "FAIL");

        Map<String, String> notRun = allGatesPass();
        notRun.put("HG-TESTS", "NOT_RUN");
        expect("NOT_RUN hard gate -> FAIL", computeVerdict(notRun, checklist(Map.of())).verdict(), "FAIL");

        expect("NA on a never-N/A blocking check (T1) -> FAIL",
                computeVerdict(allGatesPass(), checklist(Map.of("T1", "NA"))).verdict(), "FAIL");

        Map<String, String[]> missingRow = checklist(Map.of());
        missingRow.remove("C1");
        expect("missing checklist row -> FAIL (NO_EVIDENCE)",
                computeVerdict(allGatesPass(), missingRow).verdict(), "FAIL");

        Map<String, String[]> noEvidence = checklist(Map.of());
        noEvidence.put("Q1", new String[] {"PASS", ""});
        Verdict ne = computeVerdict(allGatesPass(), noEvidence);
        expect("PASS without evidence counts as FAIL", ne.nonBlockingFailed(), List.of("Q1"));

        Map<String, String[]> garbage = checklist(Map.of());
        garbage.put("Q2", new String[] {"MOSTLY", "x"});
        expect("unparseable result counts as FAIL",
                computeVerdict(allGatesPass(), garbage).nonBlockingFailed(), List.of("Q2"));

        String conflicting = "| A1 | PASS | X.java:1 |\n| A1 | FAIL | X.java:1 |\n";
        expect("conflicting duplicate rows -> CONFLICT", parseChecklist(conflicting).get("A1")[0], "CONFLICT");

        Map<String, String> allNa = new LinkedHashMap<>();
        CHECKS.stream().filter(c -> c.dim().equals("D1") && c.naAllowed()).forEach(c -> allNa.put(c.id(), "NA"));
        expect("dimension with only one applicable passing check scores full weight",
                computeVerdict(allGatesPass(), checklist(allNa)).dimScores().get("D1"), 35.0);
    }

    static void selftestRouting() {
        System.out.println("routing (CLAUDE.md §5.3):");
        Verdict pass = computeVerdict(allGatesPass(), checklist(Map.of()));
        Verdict fail = computeVerdict(allGatesPass(), checklist(Map.of("T1", "FAIL")));
        Verdict cond = computeVerdict(allGatesPass(), checklist(Map.of("A4", "FAIL", "A5", "FAIL", "C6", "FAIL",
                "T2", "FAIL")));
        Verdict ambiguous = computeVerdict(allGatesPass(), Map.of());
        expect("PASS at iteration 1 -> ADVANCE", route(pass, 1, 3, false, List.of()).nextAction(), "ADVANCE");
        expect("CONDITIONAL_PASS -> ADVANCE", route(cond, 2, 3, false, List.of()).nextAction(), "ADVANCE");
        expect("FAIL at iteration 1 -> RETRY_GENERATOR", route(fail, 1, 3, false, List.of()).nextAction(),
                "RETRY_GENERATOR");
        expect("FAIL at iteration 3 -> ESCALATE", route(fail, 3, 3, false, List.of()).nextAction(), "ESCALATE");
        expect("ambiguous checklist, first time -> REEVALUATE", route(ambiguous, 1, 3, false, List.of()).nextAction(),
                "REEVALUATE");
        Route again = route(ambiguous, 1, 3, true, List.of());
        expect("ambiguous checklist after retry -> FAIL + RETRY_GENERATOR",
                again.effectiveVerdict() + " " + again.nextAction(), "FAIL RETRY_GENERATOR");
    }

    static void selftestContractAndGitGates() throws IOException {
        System.out.println("contract, scope, protected, suppress, AC trace:");
        String contract = "# Sprint 1 Contract\n| Allowed paths | `src/activities/src/main/java/**`, "
                + "src/activities/src/test/java/**, src/shared/src/main/java/com/storeops/shared/events/** |\n"
                + "### AC-1.1 — ok [verify: routes-test]\n### AC-1.2 — err [verify: service-test]\n"
                + "### AC-1.10 — ten [verify: service-test]\n";
        Contract c = parseContract(contract);
        expect("parses 3 allowed globs", c.allowed().size(), 3);
        expect("parses AC ids in order", c.acIds(), List.of("AC-1.1", "AC-1.2", "AC-1.10"));
        Path p = Paths.get("contract.md");
        expect("in-scope change -> HG-SCOPE PASS", scopeGate(Set.of(
                "src/activities/src/main/java/com/storeops/activities/service/ActivityService.java",
                "src/shared/src/main/java/com/storeops/shared/events/SlaBreachedEvent.java"), c, p).result(), "PASS");
        expect("out-of-scope change -> HG-SCOPE FAIL", scopeGate(Set.of(
                "src/alerts/src/main/java/com/storeops/alerts/service/AlertService.java"), c, p).result(), "FAIL");
        expect("missing contract -> HG-SCOPE FAIL", scopeGate(Set.of(), null, p).result(), "FAIL");
        expect("config edit -> HG-PROTECTED FAIL", protectedGate(Set.of("config/checkstyle/checkstyle.xml")).result(),
                "FAIL");
        expect("ArchitectureRulesTest edit -> HG-PROTECTED FAIL", protectedGate(Set.of(
                "src/app/src/test/java/com/storeops/architecture/ArchitectureRulesTest.java")).result(), "FAIL");
        expect("module pom edit is not protected", protectedGate(Set.of("src/reports/pom.xml")).result(), "PASS");
        String diff = "diff --git a/src/a/X.java b/src/a/X.java\n+++ b/src/a/X.java\n"
                + "+    @SuppressWarnings(\"unchecked\")\n+++ b/README.md\n+@Disabled is fine in docs\n";
        List<String> added = addedSourceLines(diff, Set.of());
        expect("suppression in added src line -> HG-SUPPRESS FAIL", suppressGate(added).result(), "FAIL");
        expect("suppression outside src/ ignored", added.size(), 1);
        expect("AC tagged test present -> HG-AC-TRACE PASS",
                acTraceGate(c, p, Set.of("AC-1.1", "AC-1.2", "AC-1.10")).result(), "PASS");
        Gate missing = acTraceGate(c, p, Set.of("AC-1.1", "AC-1.10"));
        expect("untagged AC -> HG-AC-TRACE FAIL naming it", missing.result() + " " + missing.evidence(),
                "FAIL no @DisplayName test for: AC-1.2");
        Matcher m = AC_TAG.matcher("@DisplayName(\"AC-1.10 x\") @DisplayName(\"AC-1.1: y\")");
        List<String> tags = new ArrayList<>();
        while (m.find()) {
            tags.add(m.group(1));
        }
        expect("AC tag regex distinguishes AC-1.1 from AC-1.10", tags, List.of("AC-1.10", "AC-1.1"));
    }

    static void selftestDeterminism() {
        System.out.println("determinism:");
        Map<String, String[]> rows = checklist(Map.of("T5", "FAIL", "Q4", "FAIL", "A2", "NA"));
        String first = renderVerdict("1", "1", allGatesPass(), computeVerdict(allGatesPass(), rows));
        boolean identical = true;
        for (int i = 0; i < 200; i++) {
            identical &= first.equals(renderVerdict("1", "1", allGatesPass(), computeVerdict(allGatesPass(), rows)));
        }
        expect("200 recomputations of the same inputs are byte-identical", identical, true);
    }

    static final Pattern SKILL_CHECK_ROW = Pattern.compile("^\\|\\s*([ACTQ]\\d)\\s*\\|");
    static final Pattern SKILL_DIM_HEADING = Pattern.compile("^###\\s*(D\\d)\\b");
    static final Pattern SKILL_WEIGHT_ROW = Pattern.compile("^\\|\\s*(D\\d)\\s*\\|[^|]*\\|\\s*\\*\\*(\\d+)%\\*\\*");

    static void selftestRegistryMatchesSkill() {
        System.out.println("registry matches .harness/skills/grading-criteria/SKILL.md:");
        Path skill = Paths.get(".harness", "skills", "grading-criteria", "SKILL.md");
        if (!Files.exists(skill)) {
            expect("grading-criteria skill file exists (run from repo root)", false, true);
            return;
        }
        Map<String, Integer> weights = new LinkedHashMap<>();
        List<Check> parsed = new ArrayList<>();
        String dim = null;
        for (String l : readLenient(skill).lines().collect(Collectors.toList())) {
            Matcher h = SKILL_DIM_HEADING.matcher(l);
            if (h.find()) {
                dim = h.group(1);
            }
            Matcher w = SKILL_WEIGHT_ROW.matcher(l);
            if (w.find()) {
                weights.put(w.group(1), Integer.valueOf(w.group(2)));
            }
            Matcher r = SKILL_CHECK_ROW.matcher(l);
            if (r.find() && dim != null) {
                String[] c = cells(l);
                boolean blocking = c[c.length - 2].equalsIgnoreCase("yes");
                boolean naAllowed = !c[c.length - 1].equalsIgnoreCase("never");
                parsed.add(new Check(c[0], dim, blocking, naAllowed));
            }
        }
        expect("weights match GC-1", weights, WEIGHTS);
        expect("weights sum to 100", WEIGHTS.values().stream().mapToInt(Integer::intValue).sum(), 100);
        expect("checks (id, dimension, blocking, N/A permitted) match GC-2", parsed, CHECKS);
        for (String d : WEIGHTS.keySet()) {
            expect("dimension " + d + " has at least one check", CHECKS.stream().anyMatch(c -> c.dim().equals(d)), true);
        }
    }

    // ------------------------------------------------------------------ helpers

    static String git(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.quotepath=off");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String outText = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int code = p.waitFor();
        if (code != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " exited " + code);
        }
        return outText;
    }

    static Set<String> lines(String text) {
        Set<String> set = new TreeSet<>();
        text.lines().map(String::strip).filter(s -> !s.isEmpty()).forEach(set::add);
        return set;
    }

    static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }

    static List<String> concat(List<String> a, List<String> b) {
        List<String> l = new ArrayList<>(a);
        l.addAll(b);
        return l;
    }

    static Map<String, String> frontMatter(String text) {
        Map<String, String> fm = new LinkedHashMap<>();
        List<String> ls = text.lines().collect(Collectors.toList());
        if (ls.isEmpty() || !ls.get(0).strip().equals("---")) {
            return fm;
        }
        for (int i = 1; i < ls.size() && !ls.get(i).strip().equals("---"); i++) {
            String l = ls.get(i);
            int colon = l.indexOf(':');
            if (colon > 0) {
                String value = l.substring(colon + 1);
                int comment = value.indexOf(" #");
                fm.put(l.substring(0, colon).strip(), (comment >= 0 ? value.substring(0, comment) : value).strip());
            }
        }
        return fm;
    }

    static String[] cells(String line) {
        String t = line.strip();
        if (!t.startsWith("|")) {
            return new String[0];
        }
        String[] parts = t.substring(1, t.endsWith("|") ? t.length() - 1 : t.length()).split("\\|", -1);
        for (int i = 0; i < parts.length; i++) {
            parts[i] = parts[i].replace("**", "").replace("`", "").strip();
        }
        return parts;
    }

    static String cell(String s) {
        String t = s == null ? "" : s.replace('|', '/').replace('\n', ' ').strip();
        return t.length() > 220 ? t.substring(0, 217) + "..." : t;
    }

    static String listOrNone(List<String> l) {
        return l.isEmpty() ? "none" : String.join(", ", l);
    }

    static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    static String readLenient(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void write(Path p, String content) {
        try {
            Files.createDirectories(p.toAbsolutePath().getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private HarnessGates() {
    }
}
