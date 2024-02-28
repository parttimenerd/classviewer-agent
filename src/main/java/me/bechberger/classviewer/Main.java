package me.bechberger.classviewer;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.util.JavalinLogger;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class Main {

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    private static int parsePortFromArgs(String args) {
        int port = 7070;
        if (args != null) {
            String[] split = args.split("=");
            if (split.length == 2 && split[0].equals("port")) {
                try {
                    port = Integer.parseInt(split[1]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number: " + split[1]);
                }
            } else if (!args.isEmpty()) {
                System.err.println("Invalid argument: " + args + ", expected 'port=...'");
            }
        }
        return port;
    }

    private static Instrumentation inst;

    public static void agentmain(String args, Instrumentation inst) {
        Main.inst = inst;
        int port = parsePortFromArgs(args);
        System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "ERROR");
        Thread thread = new Thread(() -> {
            System.out.println("Starting Javalin on port " + port);
            JavalinLogger.startupInfo = false;
            var app = Javalin.create()
                    .get("/", Main::help)
                    .get("/help", Main::help)
                    .get("/list", ctx -> list(ctx, null))
                    .get("/list/<match>", ctx -> list(ctx, ctx.pathParam("match")))
                    .get("/decompile/<match>", ctx -> decompileMultiple(ctx, ctx.pathParam("match")))
                    .start(port);
        });
        thread.setDaemon(true);
        thread.start();
    }

    private static void help(Context ctx) {
        ctx.contentType("text/html");
        ctx.result("""
                <!DOCTYPE html>
                <h1>Class Viewer</h1>
                <p>Usage:</p>
                <ul>
                    <li><a href="/help">help</a>: Show this help</li>
                    <li><a href="/list">list</a>: List all classes</li>
                    <li><a href="/list/{pattern}">list/{pattern}</a>: List classes matching the given glob pattern
                        <ul>
                            <li>Examples: <a href="/list/java.util.*">java.util.*</a>,
                            <a href="/list/java.util.Map">java.util.Map</a></li>
                        </ul>
                    </li>
                    <li>
                        <a href="/decompile/{pattern}">decompile/{pattern}</a>: Decompile all classes matching the given glob pattern
                        <ul>
                            <li>Examples: <a href="/decompile/java.util.*">java.util.*</a>,
                            <a href="/decompile/java.util.Map">java.util.Map</a></li>
                        </ul>
                    </li>
                </ul>
                """);
    }

    private static List<Class> getClasses(String pattern) {
        String regexp = pattern.replace(".", "\\.").replace("$", "\\$").replace("*", ".*");
        var p = Pattern.compile(regexp).asMatchPredicate();
        return Arrays.stream(inst.getAllLoadedClasses()).filter(c -> p.test(c.getName())).toList();
    }

    private static void list(Context ctx, @Nullable String match) {
        String pattern = match == null ? "*" : match;
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>");
        sb.append("<h1>Classes</h1>");
        sb.append("<ul>");
        for (Class klass : getClasses(pattern)) {
            if (inst.isModifiableClass(klass)) {
                sb.append("<li>")
                        .append("<a href='/decompile/")
                        .append(klass.getName()).append("'>")
                        .append(klass.getName()).append("</a>")
                        .append("</li>");
            } else {
                sb.append("<li>").append(klass.getName()).append("</li>");
            }
        }
        sb.append("</ul>");
        ctx.contentType("text/html");
        ctx.result(sb.toString());
    }

    private static void decompileMultiple(Context ctx, String pattern) {
        decompile(ctx, getClasses(pattern));
    }

    /** Decompile the given classes, format them and pretty print them in formatted html */
    private static void decompile(Context ctx, List<Class> classes) {
        ctx.contentType("text/html");
        StringBuilder sb = new StringBuilder();
        sb.append("""
                <!DOCTYPE html>
                <head>
                <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/themes/prism-tomorrow.css"/>
                </head>
                <body>
                <script src="https://cdn.jsdelivr.net/npm/prismjs@1.29.0/prism.min.js"></script>
                <script src="https://cdnjs.cloudflare.com/ajax/libs/prism/9000.0.1/components/prism-java.min.js"></script>
                """);
        decompileClasses(classes.stream().filter(c -> inst.isModifiableClass(c)).toList()).forEach((c, s) -> {
            sb.append("<h1>").append(c.getName()).append("</h1>");
            sb.append("<pre><code class='language-java'>");
            sb.append(s.replace("<", "&lt;").replace(">", "&gt;"));
            sb.append("</code></pre>");
        });
        sb.append("</body>");
        ctx.result(sb.toString());
    }

    private static Map<Class, String> decompileClasses(List<Class> classes) {
        var oldOut = System.out;
        try {
            System.setOut(new java.io.PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                }
            }));
            var tmpDir = Files.createTempDirectory("classviewer");

            Map<Class, String> result = new HashMap<>();
            List<Path> classPaths = new ArrayList<>();
            for (Class c : classes) {
                var packageName = c.getPackageName();
                var path = packageName.isEmpty() ? tmpDir : tmpDir.resolve(packageName.replace(".", "/"));
                var classPath = path.resolve(c.getSimpleName() + ".class");
                Files.createDirectories(path);
                Files.write(classPath, getBytecode(c));
                classPaths.add(classPath);
            }
            String[] args = new String[classPaths.size() + 2];
            args[0] = "-jrt=1";
            for (int i = 0; i < classPaths.size(); i++) {
                args[i + 1] = classPaths.get(i).toString();
            }
            args[classes.size() + 1] = tmpDir.toString();
            ConsoleDecompiler.main(args);
            for (Class c : classes) {
                var path = tmpDir.resolve(c.getSimpleName() + ".java");
                if (Files.exists(path)) {
                    result.put(c, Files.readString(path));
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            System.setOut(oldOut);
        }
    }

    private static byte[] getBytecode(Class clazz) {
        byte[][] bytes = {null};
        var transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, java.security.ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (classBeingRedefined.equals(clazz)) {
                    bytes[0] = classfileBuffer;
                }
                return classfileBuffer;
            }
        };
        inst.addTransformer(transformer, true);
        try {
            inst.retransformClasses(clazz);
        } catch (Exception e) {
            e.printStackTrace();
        }
        var time = System.currentTimeMillis();
        while (bytes[0] == null && System.currentTimeMillis() - time < 100) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        inst.removeTransformer(transformer);
        return bytes[0];
    }
}