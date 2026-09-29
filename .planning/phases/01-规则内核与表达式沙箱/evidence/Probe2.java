import com.googlecode.aviator.*;
import java.lang.management.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class Probe2 {
    static void hdr(String s) { System.out.println("\n##### " + s); }
    static String run(AviatorEvaluatorInstance inst, String expr, Map<String,Object> env) {
        try { Object r = inst.execute(expr, env); return "OK:" + r + " (" + (r==null?"null":r.getClass().getName()) + ")"; }
        catch (Throwable t) { String m = t.getMessage(); if (m!=null&&m.length()>120) m=m.substring(0,120)+"...";
            return "THROW:" + t.getClass().getName() + ": " + m; }
    }
    static String pad(String s, int n){ if(s.length()>=n) return s.substring(0,n); StringBuilder b=new StringBuilder(s); while(b.length()<n) b.append(' '); return b.toString(); }

    public static void main(String[] a) throws Exception {
        AviatorEvaluatorInstance A = AviatorEvaluator.newInstance();
        A.enableSandboxMode();
        Map<String,Object> env = new HashMap<String,Object>();
        env.put("amount", 10000L);
        env.put("channel", "POS");
        env.put("s", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        hdr("11. REGEX / ReDoS -- WHAT IS THE ACTUAL SYNTAX?");
        for (String e : new String[]{
            "=~ 'a' 'a'",
            "'a' =~ 'a'",
            "s =~ '^(a+)+$'",
            "s =~ '^(a+)+$' 'timeout'",
            "s !~ 'b'",
            "s =~ '(a+)+b'",
            "str.find(s, '(a+)+b')",
            "string.find(s, '(a+)+b')",
            "seq.contains(s, 'a')"
        }) {
            long t0 = System.nanoTime();
            String r = run(A, e, env);
            long ms = (System.nanoTime()-t0)/1000000;
            System.out.println("  " + pad(e, 30) + " => " + pad(r, 60) + " (" + ms + " ms)");
        }

        hdr("12. StaticFields feature is ENABLED -- can it be used?");
        for (String e : new String[]{
            "java.lang.Runtime",
            "java.lang.Runtime.RUNTIME_VERSION",
            "java.lang.Integer.MAX_VALUE",
            "java.lang.System",
            "java.lang.System.out",
            "Integer.MAX_VALUE",
            "use java.lang; Integer.MAX_VALUE"
        }) System.out.println("  " + pad(e, 36) + " => " + run(A, e, env));

        hdr("13. 'amount.class' ESCALATION -- can a Class object reach code execution?");
        for (String e : new String[]{
            "amount.class",
            "amount.class.name",
            "amount.class.getName()",
            "amount.class.forName('x')",
            "amount.class.classLoader",
            "amount.class.newInstance()",
            "string.contains(amount.class, '')",
            "amount.class.methods"
        }) System.out.println("  " + pad(e, 40) + " => " + run(A, e, env));

        hdr("14. disable Lambda/Fn -- does that kill recursion + StackOverflowError?");
        AviatorEvaluatorInstance NF = AviatorEvaluator.newInstance();
        NF.enableSandboxMode();
        NF.disableFeature(Feature.Lambda);
        NF.disableFeature(Feature.Fn);
        NF.disableFeature(Feature.StaticFields);
        NF.disableFeature(Feature.Assignment);
        NF.disableFeature(Feature.Let);
        NF.disableFeature(Feature.ForLoop);
        NF.disableFeature(Feature.WhileLoop);
        NF.disableFeature(Feature.Use);
        NF.disableFeature(Feature.StringInterpolation);
        NF.disableFeature(Feature.ExceptionHandle);
        NF.disableFeature(Feature.If);
        NF.disableFeature(Feature.Return);
        NF.disableFeature(Feature.LexicalScope);
        kv("featureSet after narrowing", NF.getOptionValue(Options.FEATURE_SET));
        for (String e : new String[]{
            "fn f(n) { return f(n+1); } ; f(0)",
            "let x = 1; x > 0",
            "for x in range(0,10) { }",
            "while(false) { }",
            "amount > 100 && channel == 'POS'",
            "amount > 100 ? 1 : 0",
            "amount > 100 || channel == 'POS'",
            "string.length(channel) > 1",
            "count(split(channel, ','))",
            "amount > 100 && (channel == 'POS' || channel == 'ATM')",
            "amount > 100 == true"
        }) System.out.println("  " + pad(e, 56) + " => " + run(NF, e, env));

        hdr("15. addOpFunction STEP-COUNTER FEASIBILITY");
        kv("addOpFunction present", hasMethod(AviatorEvaluatorInstance.class, "addOpFunction"));
        for (String m : new String[]{"addOpFunction","removeFunction","addFunction"}) kv("  " + m, hasMethod(AviatorEvaluatorInstance.class, m));
        // Demonstrate the counting-wrapper shape actually compiles & works on JDK 8
        try {
            Class<?> av = Class.forName("com.googlecode.aviator.runtime.type.AviatorFunction");
            Class<?> ao = Class.forName("com.googlecode.aviator.runtime.type.AviatorObject");
            Class<?> opIface = Class.forName("com.googlecode.aviator.runtime.operator.AviatorOperator");
            for (Method m : ao.getMethods()) if (m.getName().equals("getName")) kv("  AviatorObject.getName", m.getReturnType().getName());
            for (Method m : opIface.getMethods()) { if (m.getName().equals("getName")) kv("  AviatorOperator.getName", m.getReturnType().getName()); }
            for (Method m : av.getMethods()) if (m.getName().equals("getName")) kv("  AviatorFunction.getName", m.getReturnType().getName());
            kv("AviatorOperator class", opIface.getName());
        } catch (Throwable t) { kv("op iface err", t); }

        hdr("16. METASPACE / CLASS-COUNT: validate() vs compile()");
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        long base = cl.getTotalLoadedClassCount();
        AviatorEvaluatorInstance V = AviatorEvaluator.newInstance();
        V.enableSandboxMode();
        String[] bad = {"amount > ", "(((((", "amount ++++ ", "'unterminated", "fn ( {"};
        for (int i=0;i<20000;i++) { try { V.validate(bad[i%bad.length] + " " + i); } catch (Throwable t) {} }
        long afterValidate = cl.getTotalLoadedClassCount();
        for (int i=0;i<2000;i++) { try { V.compile("amount > " + i, false); } catch (Throwable t) {} }
        long afterCompileValid = cl.getTotalLoadedClassCount();
        long before2 = cl.getTotalLoadedClassCount();
        for (int i=0;i<20000;i++) { try { V.compile(bad[i%bad.length] + " " + i, false); } catch (Throwable t) {} }
        long afterCompileBad = cl.getTotalLoadedClassCount();
        System.out.println("  loaded classes base                     = " + base);
        System.out.println("  +20000x validate(malformed)             = " + afterValidate + "   (delta " + (afterValidate-base) + ")");
        System.out.println("  +20000x compile(malformed)  [BYPASS]    = " + afterCompileBad + "   (delta " + (afterCompileBad-before2) + ")");
        System.out.println("  +2000x  compile(valid, cached=false)     = " + afterCompileValid + "   (delta " + (afterCompileValid-afterValidate) + ")");
        kv("metaspace committed", ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getName().contains("Metaspace")).map(p->p.getUsage().getCommitted()/(1024*1024)+"MB").findFirst().orElse("?"));

        hdr("17. LRU CACHE IS BOUNDED?");
        AviatorEvaluatorInstance C = AviatorEvaluator.newInstance();
        C.enableSandboxMode();
        C.useLRUExpressionCache(50);
        long c0 = cl.getTotalLoadedClassCount();
        for (int i=0;i<5000;i++) { try { C.execute("amount > " + i, env); } catch (Throwable t) {} }
        long c1 = cl.getTotalLoadedClassCount();
        System.out.println("  LRU(50): 5000 distinct execute() -> loaded classes delta = " + (c1-c0));
        AviatorEvaluatorInstance U = AviatorEvaluator.newInstance();
        U.enableSandboxMode();
        long u0 = cl.getTotalLoadedClassCount();
        for (int i=0;i<5000;i++) { try { U.execute("amount > " + i, env); } catch (Throwable t) {} }
        long u1 = cl.getTotalLoadedClassCount();
        System.out.println("  no-LRU : 5000 distinct execute() -> loaded classes delta = " + (u1-u0));

        hdr("18. COMPILE(expr, cached) IDENTITY / re-compile cost");
        AviatorEvaluatorInstance E = AviatorEvaluator.newInstance();
        E.enableSandboxMode();
        Expression e1 = E.compile("amount > 100", true);
        Expression e2 = E.compile("amount > 100", true);
        kv("compile(cached=true) returns same instance", e1 == e2);
        long t0 = System.nanoTime();
        for (int i=0;i<20000;i++) E.compile("amount > 100", true);
        kv("20k cached compile() ns/op", (System.nanoTime()-t0)/20000);
        t0 = System.nanoTime();
        for (int i=0;i<20000;i++) e1.execute(env);
        kv("20k precompiled execute() ns/op", (System.nanoTime()-t0)/20000);
        Map<String,Object> e2env = new HashMap<String,Object>(); e2env.put("amount",10000L);
        t0 = System.nanoTime();
        for (int i=0;i<20000;i++) E.execute("amount > 100", e2env);
        kv("20k execute(String,env) ns/op", (System.nanoTime()-t0)/20000);

        hdr("19. STACKOVERFLOW IS AN Error, NOT an Exception");
        try { A.execute("fn f(n){ return f(n+1); }; f(0)", env); }
        catch (Exception ex) { System.out.println("  caught as Exception: " + ex); }
        catch (Throwable t)  { System.out.println("  NOT caught by catch(Exception) -> " + t.getClass().getName()); }

        hdr("20. USE_USER_ENV_AS_TOP_ENV_DIRECTLY (mutability of host env)");
        AviatorEvaluatorInstance W = AviatorEvaluator.newInstance();
        W.enableSandboxMode();
        Map<String,Object> mut = new HashMap<String,Object>();
        mut.put("amount", 10000L);
        kv("default USE_USER_ENV_AS_TOP_ENV_DIRECTLY", W.getOptionValue(Options.USE_USER_ENV_AS_TOP_ENV_DIRECTLY).bool);
        for (String e : new String[]{"amount = 999", "__env__", "let a = 1; a"}) {
            Map<String,Object> copy = new HashMap<String,Object>(mut);
            System.out.println("  " + pad(e, 20) + " => " + run(W, e, copy) + "   host map now: " + copy);
        }

        System.out.println("\n##### DONE2");
    }
    static boolean hasMethod(Class<?> c, String n){ for (Method m : c.getMethods()) if (m.getName().equals(n)) return true; return false; }
    static void kv(String k, Object v){ System.out.println("  " + k + " = " + v); }
}
