// Loads and links every .class file under a directory with the bytecode
// verifier forced on, to check that bodies emitted by the reconstruction
// (baseline --emit-classes) are loadable JVM bytecode, not just something
// Soot's writer accepted.
//
// Usage: java -Xverify:all -cp <libs> scripts/VerifyClasses.java <classes-dir>
// (<libs> = kotlin-stdlib + kotlinx-coroutines-core, as in run_baseline.sh)
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

public class VerifyClasses {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        List<String> names;
        try (Stream<Path> s = Files.walk(root)) {
            names = s.filter(p -> p.toString().endsWith(".class"))
                    .map(p -> root.relativize(p).toString().replace('/', '.').replaceAll("\\.class$", ""))
                    .sorted().toList();
        }
        int ok = 0, bad = 0;
        try (URLClassLoader loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, VerifyClasses.class.getClassLoader())) {
            for (String n : names) {
                try {
                    // initialize=true forces linking, which is where verification happens
                    Class.forName(n, true, loader);
                    ok++;
                } catch (Throwable t) {
                    bad++;
                    System.out.println("FAIL " + n + ": " + t);
                }
            }
        }
        System.out.println("verified " + ok + " / " + names.size() + " classes, " + bad + " failed");
        System.exit(bad == 0 ? 0 : 1);
    }
}
