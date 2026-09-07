// Build-time-only tool (run on a real JVM, never shipped). Scans the exact
// classpath scalino-dotc's native-image build bakes in (compiler jar(s) +
// nscplugin jar) for every class assignable from
// dotty.tools.dotc.transform.MegaPhase$MiniPhase, and for each one records
// which of MiniPhase's overridable prepareFor*/transform* methods it
// declares -- using real JVM reflection, here, once, offline.
//
// This produces the exact same fact MegaPhase.scala's `defines` used to
// compute at runtime via `cls.getDeclaredMethods` -- Scala Native has no
// general reflection API to do that lookup at runtime (see docs/findings.md
// "Blocker A"), so we compute it here instead and bake it into a generated
// Scala source file consumed as a plain, reflection-free Map.
//
// Usage: GenMiniPhaseOverrides <colon-separated-classpath> <output-.scala-path>
import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class GenMiniPhaseOverrides {

  // Keep in sync with MegaPhase.MiniPhase's overridable prepareFor*/transform*
  // methods (vendor/scala3/compiler/src/dotty/tools/dotc/transform/MegaPhase.scala).
  static final Set<String> OVERRIDABLE_NAMES = new HashSet<>(java.util.Arrays.asList(
    "prepareForIdent", "prepareForSelect", "prepareForThis", "prepareForSuper", "prepareForApply",
    "prepareForTypeApply", "prepareForLiteral", "prepareForNew", "prepareForTyped", "prepareForAssign",
    "prepareForBlock", "prepareForIf", "prepareForClosure", "prepareForMatch", "prepareForCaseDef",
    "prepareForLabeled", "prepareForReturn", "prepareForWhileDo", "prepareForTry", "prepareForSeqLiteral",
    "prepareForInlined", "prepareForQuote", "prepareForSplice", "prepareForTypeTree", "prepareForBind",
    "prepareForAlternative", "prepareForUnApply", "prepareForValDef", "prepareForDefDef", "prepareForTypeDef",
    "prepareForTemplate", "prepareForPackageDef", "prepareForStats", "prepareForUnit", "prepareForOther",
    "transformIdent", "transformSelect", "transformThis", "transformSuper", "transformApply",
    "transformTypeApply", "transformLiteral", "transformNew", "transformTyped", "transformAssign",
    "transformBlock", "transformIf", "transformClosure", "transformMatch", "transformCaseDef",
    "transformLabeled", "transformReturn", "transformWhileDo", "transformTry", "transformSeqLiteral",
    "transformInlined", "transformQuote", "transformSplice", "transformTypeTree", "transformBind",
    "transformAlternative", "transformUnApply", "transformValDef", "transformDefDef", "transformTypeDef",
    "transformTemplate", "transformPackageDef", "transformStats", "transformUnit", "transformOther"
  ));

  static final String MINI_PHASE = "dotty.tools.dotc.transform.MegaPhase$MiniPhase";

  public static void main(String[] args) throws Exception {
    if (args.length != 2) {
      System.err.println("usage: GenMiniPhaseOverrides <classpath> <out.scala>");
      System.exit(1);
    }
    String cp = args[0];
    File outFile = new File(args[1]);

    String[] jarPaths = cp.split(":");
    List<URL> urls = new ArrayList<>();
    List<String> classNames = new ArrayList<>();
    for (String p : jarPaths) {
      if (p.isEmpty()) continue;
      File f = new File(p);
      if (!f.exists() || !p.endsWith(".jar")) continue;
      urls.add(f.toURI().toURL());
      try (JarFile jar = new JarFile(f)) {
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
          JarEntry e = entries.nextElement();
          String name = e.getName();
          if (!name.endsWith(".class")) continue;
          if (name.equals("module-info.class") || name.endsWith("/module-info.class")) continue;
          classNames.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
        }
      }
    }

    URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getSystemClassLoader());
    Class<?> miniPhaseClass;
    try {
      miniPhaseClass = Class.forName(MINI_PHASE, false, loader);
    } catch (ClassNotFoundException ex) {
      System.err.println("could not load " + MINI_PHASE + " from the given classpath -- is scala3-compiler_3 really on it?");
      throw ex;
    }

    TreeMap<String, Set<String>> table = new TreeMap<>();
    int skipped = 0;
    for (String className : classNames) {
      Class<?> cls;
      try {
        cls = Class.forName(className, false, loader);
      } catch (Throwable ex) {
        // Best-effort: some classfiles on the classpath won't link outside a
        // real compiler run (missing optional deps, etc). Any MiniPhase
        // subclass genuinely missed here fails loudly and specifically at
        // MegaPhase.defines' runtime lookup (see MegaPhase.scala), not
        // silently -- so skipping unlinkable classes here is safe.
        skipped++;
        continue;
      }
      if (cls == miniPhaseClass) continue;
      if (!miniPhaseClass.isAssignableFrom(cls)) continue;

      Set<String> names = new HashSet<>();
      for (Method m : cls.getDeclaredMethods()) {
        if (OVERRIDABLE_NAMES.contains(m.getName())) names.add(m.getName());
      }
      table.put(cls.getName(), names);
    }

    System.err.println("GenMiniPhaseOverrides: found " + table.size() + " MiniPhase subclasses ("
        + skipped + " classpath entries skipped as unlinkable/irrelevant)");
    if (table.isEmpty()) {
      throw new IllegalStateException("found zero MiniPhase subclasses -- classpath is almost certainly wrong");
    }

    outFile.getParentFile().mkdirs();
    try (PrintWriter w = new PrintWriter(outFile, "UTF-8")) {
      w.println("// GENERATED by build/tools/GenMiniPhaseOverrides.java (build/02b-gen-megaphase-overrides.sh).");
      w.println("// Do not edit by hand -- re-run the generator after any MiniPhase subclass's");
      w.println("// overridden methods change. Consumed by MegaPhase.scala's `defines`.");
      w.println("package dotty.tools.dotc.transform");
      w.println();
      w.println("object MiniPhaseOverrides {");
      w.println("  val declaredMethodNames: Map[String, Set[String]] = Map(");
      boolean first = true;
      for (java.util.Map.Entry<String, Set<String>> e : table.entrySet()) {
        StringBuilder sb = new StringBuilder();
        sb.append(first ? "    " : ",\n    ");
        first = false;
        sb.append('"').append(e.getKey()).append("\" -> Set(");
        boolean firstName = true;
        List<String> sorted = new ArrayList<>(e.getValue());
        java.util.Collections.sort(sorted);
        for (String n : sorted) {
          if (!firstName) sb.append(", ");
          firstName = false;
          sb.append('"').append(n).append('"');
        }
        sb.append(")");
        w.print(sb);
      }
      w.println();
      w.println("  )");
      w.println("}");
    }
    System.err.println("OK: " + outFile);
  }
}
