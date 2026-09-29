import com.googlecode.aviator.*;
import com.googlecode.aviator.runtime.type.AviatorObject;

import java.lang.reflect.Method;
import java.util.*;

/** Empirical probe of Aviator 5.4.4 on JDK 8. Prints a machine-readable report. */
public class Probe {

    static void hdr(String s) { System.out.println("\n##### " + s); }
    static void kv(String k, Object v) { System.out.println("  " + k + " = " + v); }

    static String run(AviatorEvaluatorInstance inst, String expr, Map<String,Object> env) {
        try {
            Object r = inst.execute(expr, env);
            return "OK:" + r + " (" + (r==null?"null":r.getClass().getSimpleName()) + ")";
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m != null && m.length() > 140) m = m.substring(0,140) + "...";
            return "THROW:" + t.getClass().getName() + ": " + m;
        }
    }

    static String runCompiled(AviatorEvaluatorInstance inst, String expr, Map<String,Object> env) {
        try {
            Expression e = inst.compile(expr, true);
            Object r = e.execute(env);
            return "OK:" + r + " (" + (r==null?"null":r.getClass().getSimpleName()) + ")";
        } catch (Throwable t) {
            String m = t.getMessage();
            if (m != null && m.length() > 140) m = m.substring(0,140) + "...";
            return "THROW:" + t.getClass().getName() + ": " + m;
        }
    }

    public static void main(String[] args) throws Exception {
        kv("java.version", System.getProperty("java.version"));
        kv("java.vendor", System.getProperty("java.vendor"));

        AviatorEvaluatorInstance I = AviatorEvaluator.newInstance();

        // ---------------------------------------------------------------- 0. defaults
        hdr("0. DEFAULT OPTION VALUES (fresh instance, nothing configured)");
        for (Options o : Options.values()) {
            try { kv("default." + o.name(), String.valueOf(I.getOptionValue(o))); }
            catch (Throwable t) { kv("default." + o.name(), "ERR " + t); }
        }
        kv("Feature.getFullFeatures()", Feature.getFullFeatures());
        kv("Feature.getCompatibleFeatures()", Feature.getCompatibleFeatures());

        // ---------------------------------------------------------------- 1. API presence
        hdr("1. API SURFACE PRESENCE (5.4.4)");
        String[] statics = {"validate","execute","compile","useLRUExpressionCache","invalidateCache",
                            "newInstance","addFunction","getInstance","setOption","getOptionValue"};
        for (String s : statics) {
            boolean found = false;
            for (Method m : AviatorEvaluator.class.getMethods()) if (m.getName().equals(s)) { found = true; break; }
            kv("AviatorEvaluator." + s, found ? "PRESENT" : "ABSENT");
        }
        boolean sandbox = false;
        for (Method m : AviatorEvaluatorInstance.class.getMethods()) if (m.getName().equals("enableSandboxMode")) sandbox = true;
        kv("AviatorEvaluatorInstance.enableSandboxMode", sandbox ? "PRESENT" : "ABSENT");
        boolean isSandboxMode = false;
        for (Method m : AviatorEvaluatorInstance.class.getMethods()) if (m.getName().equals("isSandboxMode")) isSandboxMode = true;
        kv("AviatorEvaluatorInstance.isSandboxMode (readback API)", isSandboxMode ? "PRESENT" : "ABSENT");

        // ---------------------------------------------------------------- 2. nil semantics
        hdr("2. NIL SEMANTICS -- NIL_WHEN_PROPERTY_NOT_FOUND");
        Map<String,Object> env = new HashMap<String,Object>();
        env.put("amount", 10000L);
        env.put("channel", "POS");
        String[] nilExprs = {
            "missing",                    // bare read
            "missing != nil",             // explicit nil compare
            "missing == nil",             // explicit nil compare
            "missing == 1",               // nil vs number
            "missing > 100",              // nil in ordering compare
            "missing < 100",
            "missing >= 100",
            "missing + 1",                // nil in arithmetic
            "missing - 1",
            "missing * 2",
            "missing + 'x'",
            "amount > 100",               // control: present key
            "amount > 100 && missing > 0",// nil inside && chain
            "amount > 100 && missing != nil",
            "seq.list()",                // builtin sanity
            "string.contains('zzz','z')"  // builtin sanity 2
        };
        for (boolean flag : new boolean[]{false, true}) {
            AviatorEvaluatorInstance n = AviatorEvaluator.newInstance();
            n.setOption(Options.NIL_WHEN_PROPERTY_NOT_FOUND, flag);
            System.out.println("  --- NIL_WHEN_PROPERTY_NOT_FOUND=" + flag + " ---");
            for (String ex : nilExprs) System.out.println("    " + pad(ex) + " => " + run(n, ex, env));
        }

        // ---------------------------------------------------------------- 3. sandbox defaults
        hdr("3. enableSandboxMode() EFFECTIVE CONFIG (fresh instance, sandbox first)");
        AviatorEvaluatorInstance S = AviatorEvaluator.newInstance();
        S.enableSandboxMode();
        for (Options o : Options.values()) {
            try { kv("sandbox." + o.name(), String.valueOf(S.getOptionValue(o))); }
            catch (Throwable t) { kv("sandbox." + o.name(), "ERR " + t); }
        }
        kv("sandbox.featureSet", S.getOptionValue(Options.FEATURE_SET));
        kv("sandbox.ALLOWED_CLASS_SET.empty", S.getOptionValue(Options.ALLOWED_CLASS_SET).classes.isEmpty());
        kv("sandbox.ASSIGNABLE_ALLOWED_CLASS_SET.empty", S.getOptionValue(Options.ASSIGNABLE_ALLOWED_CLASS_SET).classes.isEmpty());

        hdr("3b. IS Feature.StaticFields DISABLED BY SANDBOX?");
        Set<Feature> fs = S.getOptionValue(Options.FEATURE_SET).featureSet;
        for (Feature f : Feature.values()) {
            System.out.println("    " + pad(f.name(), 22) + " enabled=" + fs.contains(f));
        }

        // ---------------------------------------------------------------- 4. attack matrix
        hdr("4. NINE-CLASS ATTACK MATRIX (sandbox mode, default options otherwise)");
        AviatorEvaluatorInstance A = AviatorEvaluator.newInstance();
        A.enableSandboxMode();
        // poison env to catch any escape that succeeds silently
        Map<String,Object> penv = new HashMap<String,Object>();
        penv.put("amount", 10000L);
        penv.put("channel", "POS");
        penv.put("java.lang.Runtime", "PRESENT-IN-ENV");

        String[][] matrix = {
            {"A1 reflection: .class",            "amount.getClass()"},
            {"A1 reflection: getClass",           "amount.class"},
            {"A1 reflection: method on obj",      "'abc'.getBytes()"},
            {"A1 reflection: static via env key", "java\\.lang\\.Runtime"},
            {"A2 ctor: new",                      "new java.lang.String('x')"},
            {"A2 ctor: seq",                      "new java.util.ArrayList()"},
            {"A3 static method",                  "java.lang.Runtime.getRuntime()"},
            {"A3 static method T()",              "T(java.lang.Runtime).getRuntime()"},
            {"A3 static: System.exit",            "java.lang.System.exit(0)"},
            {"A3 static: Integer.parseInt",       "java.lang.Integer.parseInt('1')"},
            {"A4 ClassLoader",                    "java.lang.ClassLoader.getSystemClassLoader()"},
            {"A4 Thread",                         "java.lang.Thread.currentThread()"},
            {"A4 Runtime chain",                  "java.lang.Runtime.getRuntime().exec('id')"},
            {"A4 Class.forName",                  "Class.forName('java.lang.Runtime')"},
            {"A5 file IO",                        "seq.map('x', java.io.File('.'))"},
            {"A5 file IO read",                   "io.read('file:/etc/passwd')"},
            {"A5 file IO write",                  "io.write('/tmp/x','y')"},
            {"A5 network IO",                     "http.get('http://127.0.0.1')"},
            {"A6 infinite loop while",            "let i = 0; while(true) { i = i + 1; } ; i"},
            {"A6 infinite loop for",              "for x in range(0, 2000000000) { } ; 1"},
            {"A6 deep recursion",                 "fn f(n) { return f(n+1); } ; f(0)"},
            {"A7 oversized string",               "string.repeat(string.concat('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'), 100000)"},
            {"A7 oversized array",                "let a = range(0, 20000000); #a"},
            {"A8 ReDoS regex",                    "let s = string.repeat('a', 40); =~= '(a+)+b' 'a' "},
            {"A8 regex direct",                   "=~ '^(a+)+$' 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'"},
            {"A9 deep nesting",                   "1" + repeat("+1", 300)},
            {"A9 deep nesting fn",                "fn f(x) { return x; } ; " + repeat("f(", 200) + "1" + repeat(")", 200)},
            {"A0 sanity should pass",             "amount > 100 && channel == 'POS'"},
        };
        for (String[] row : matrix) {
            String r1 = run(A, row[1], penv);
            String r2 = runCompiled(A, row[1], penv);
            System.out.println("  " + pad(row[0], 32) + " | compile+exec: " + r1);
            if (!r1.equals(r2)) System.out.println("  " + pad("", 32) + " | precompiled : " + r2);
        }

        // ---------------------------------------------------------------- 5. loop budget
        hdr("5. LOOP / TIMEOUT BUDGET REACHABILITY");
        AviatorEvaluatorInstance L = AviatorEvaluator.newInstance();
        L.enableSandboxMode();
        kv("sandbox MAX_LOOP_COUNT", L.getOptionValue(Options.MAX_LOOP_COUNT));
        kv("sandbox EVAL_TIMEOUT_MS", L.getOptionValue(Options.EVAL_TIMEOUT_MS));
        long t0 = System.nanoTime();
        String r = run(L, "let i = 0; while(true) { i = i + 1; } ; i", new HashMap<String,Object>());
        long ms = (System.nanoTime()-t0)/1000000;
        System.out.println("  while(true) under sandbox => " + r + "   (wallclock " + ms + " ms)");
        t0 = System.nanoTime();
        r = run(L, "let i=0; while(i < 100) { i = i + 1; } ; i", new HashMap<String,Object>());
        ms = (System.nanoTime()-t0)/1000000;
        System.out.println("  while(i<100)   => " + r + "   (wallclock " + ms + " ms)");
        t0 = System.nanoTime();
        r = run(L, "fn f(n) { return f(n+1); } ; f(0)", new HashMap<String,Object>());
        ms = (System.nanoTime()-t0)/1000000;
        System.out.println("  infinite recursion => " + r + "   (wallclock " + ms + " ms)");

        // Override ordering: set timeout AFTER enableSandboxMode
        AviatorEvaluatorInstance L2 = AviatorEvaluator.newInstance();
        L2.setOption(Options.EVAL_TIMEOUT_MS, 50L);
        L2.enableSandboxMode();
        kv("set-before-sandbox EVAL_TIMEOUT_MS", L2.getOptionValue(Options.EVAL_TIMEOUT_MS));
        AviatorEvaluatorInstance L3 = AviatorEvaluator.newInstance();
        L3.enableSandboxMode();
        L3.setOption(Options.EVAL_TIMEOUT_MS, 50L);
        kv("set-after-sandbox  EVAL_TIMEOUT_MS", L3.getOptionValue(Options.EVAL_TIMEOUT_MS));

        // ---------------------------------------------------------------- 6. operator interception (step budget candidate)
        hdr("6. CAN A STEP BUDGET BE BUILT ON addOpFunction?");
        try {
            Class<?> fnIface = Class.forName("com.googlecode.aviator.runtime.type.AviatorFunction");
            kv("AviatorFunction iface", fnIface.getName());
            boolean has = false;
            for (Method m : AviatorEvaluatorInstance.class.getMethods()) if (m.getName().equals("addOpFunction")) has = true;
            kv("AviatorEvaluatorInstance.addOpFunction", has ? "PRESENT" : "ABSENT");
        } catch (Throwable t) { kv("err", t); }

        // ---------------------------------------------------------------- 7. validate() vs compile() codegen
        hdr("7. validate() vs compile() ON MALFORMED EXPRESSIONS (metaspace hypothesis)");
        AviatorEvaluatorInstance V = AviatorEvaluator.newInstance();
        V.enableSandboxMode();
        String[] bad = {
            "amount > ",
            "amount ++++ ",
            "(((",
            "amount > 100 && ",
            "for x in { let y = ; }",
            "'unterminated",
            "1 +* 2",
            "fn ( {"
        };
        for (String b : bad) {
            String vr, cr;
            try { V.validate(b); vr = "PASS"; } catch (Throwable t) { vr = "REJECT(" + t.getClass().getSimpleName() + ")"; }
            try { V.compile(b, true); cr = "COMPILED"; } catch (Throwable t) { cr = "REJECT(" + t.getClass().getSimpleName() + ")"; }
            System.out.println("  " + pad("'" + b + "'", 28) + " validate=" + pad(vr, 26) + " compile=" + cr);
        }
        kv("compilation before", compCount());
        int rej = 0;
        for (int i = 0; i < 5000; i++) { try { V.validate(bad[i % bad.length] + " " + i); } catch (Throwable t) { rej++; } }
        kv("5000x validate() rejected", rej);
        kv("compilation after 5000x validate()", compCount());
        int comp = 0;
        for (int i = 0; i < 200; i++) { try { V.compile("amount > " + i, false); comp++; } catch (Throwable t) {} }
        kv("200x distinct valid compile()", comp);
        kv("compilation after 200x valid compile", compCount());

        // ---------------------------------------------------------------- 8. sandbox can be turned OFF by expression?
        hdr("8. CAN AN EXPRESSION DISABLE THE SANDBOX / RELAX OPTIONS?");
        for (String e : new String[]{
            "setOption('EVAL_TIMEOUT_MS', 0)",
            "aviator.setOption(Options.EVAL_TIMEOUT_MS, 0)",
            "require('java.lang')",
            "use java.lang",
            "load('file:/tmp/x')",
            "__env__",
            "__instance__"
        }) {
            System.out.println("  " + pad(e, 44) + " => " + run(A, e, penv));
        }

        // ---------------------------------------------------------------- 9. LRU cache
        hdr("9. LRU EXPRESSION CACHE");
        AviatorEvaluatorInstance C = AviatorEvaluator.newInstance();
        C.enableSandboxMode();
        C.useLRUExpressionCache(1000);
        for (int i = 0; i < 3000; i++) { try { C.execute("amount > " + i, penv); } catch (Throwable t) {} }
        kv("executed 3000 distinct expressions with LRU(1000)", "survived");
        C.invalidateCache("amount > 1");
        kv("invalidateCache('amount > 1')", "called (returns void)");

        // ---------------------------------------------------------------- 10. reflection env
        hdr("10. WHAT CAN AN EXPRESSION REACH THROUGH ENV?");
        Map<String,Object> oenv = new HashMap<String,Object>();
        oenv.put("dto", new Dto());
        oenv.put("list", Arrays.asList(1,2,3));
        oenv.put("map", Collections.singletonMap("k","v"));
        AviatorEvaluatorInstance R = AviatorEvaluator.newInstance();
        R.enableSandboxMode();
        for (String e : new String[]{
            "dto.name", "dto.secret", "dto.getClass()", "dto.hashCode()", "dto.toString()",
            "list.size()", "#list", "map.k", "map.size()"
        }) System.out.println("  " + pad(e, 22) + " => " + run(R, e, oenv));

        System.out.println("\n##### DONE");
    }

    static String repeat(String s, int n) {
        StringBuilder b = new StringBuilder(s.length()*n);
        for (int i=0;i<n;i++) b.append(s);
        return b.toString();
    }

    static long compCount() {
        long n = 0;
        for (Class<?> c : java.lang.management.ManagementFactory.getClassLoadingMXBean()
             .getLoadedClassCount() == 0 ? new Class<?>[0] : new Class<?>[0]) { n++; }
        try {
            java.lang.management.CompilationMXBean b = java.lang.management.ManagementFactory.getCompilationMXBean();
            return b == null ? -1 : b.getTotalCompilationTime();
        } catch (Throwable t) { return -1; }
    }

    static String pad(String s, int n) { if (s.length() >= n) return s.substring(0,n); StringBuilder b = new StringBuilder(s); while (b.length() < n) b.append(' '); return b.toString(); }
    static String pad(String s) { return pad(s, 46); }

    public static class Dto {
        public String getName() { return "alice"; }
        public String getSecret() { return "S3CRET"; }
    }
}
