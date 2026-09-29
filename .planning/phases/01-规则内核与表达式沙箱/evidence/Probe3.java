import com.googlecode.aviator.*;
import com.googlecode.aviator.exception.ExpressionRuntimeException;
import com.googlecode.aviator.lexer.token.OperatorType;
import com.googlecode.aviator.runtime.function.AbstractFunction;
import com.googlecode.aviator.runtime.type.*;
import java.lang.management.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public class Probe3 {
    static void hdr(String s) { System.out.println("\n##### " + s); }
    static String repeat(String s, int n) { StringBuilder b = new StringBuilder(); for (int i=0;i<n;i++) b.append(s); return b.toString(); }
    static String pad(String s, int n){ if(s.length()>=n) return s.substring(0,n); StringBuilder b=new StringBuilder(s); while(b.length()<n) b.append(' '); return b.toString(); }
    static long metaspaceMB() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans())
            if (p.getName().contains("Metaspace")) return p.getUsage().getCommitted()/(1024*1024);
        return -1;
    }
    // run on a daemon thread with a hard wall-clock cap so a catastrophic regex cannot hang the JVM
    static long bounded(final AviatorEvaluatorInstance inst, final String expr,
                        final Map<String,Object> env, long capMs, String[] out) {
        try {
        final long[] ms = { -1 };
        Thread t = new Thread(new Runnable(){ public void run() {
            long s = System.nanoTime();
            try { out[0] = "OK:" + inst.execute(expr, env); }
            catch (Throwable x) { out[0] = "THROW:" + x.getClass().getSimpleName()
                + (x.getMessage()==null?"":":"+x.getMessage().substring(0, Math.min(60,x.getMessage().length()))); }
            ms[0] = (System.nanoTime()-s)/1000000;
        }});
        t.setDaemon(true); t.start(); t.join(capMs);
        if (t.isAlive()) { out[0] = "DID-NOT-FINISH-WITHIN-" + capMs + "ms"; ms[0] = capMs; }
        return ms[0];
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            out[0] = "INTERRUPTED"; return -1;
        }
    }

    public static void main(String[] a) throws Exception {
        AviatorEvaluatorInstance A = AviatorEvaluator.newInstance();
        A.enableSandboxMode();
        Map<String,Object> env = new HashMap<String,Object>();
        env.put("amount", 10000L);
        env.put("channel", "POS");
        env.put("s", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");

        hdr("1. REGEX LITERAL /pattern/ WITH =~  (OperatorType MATCH swaps args: args[1].match(args[0]))");
        for (String e : new String[]{
            "s =~ /a/", "s =~ /^(a+)+b$/", "/^(a+)+b$/ =~ s", "s =~ /(a)/", "s !~ /zzz/"}) {
            String[] out = new String[1];
            long ms = bounded(A, e, env, 3000, out);
            System.out.println("  " + pad(e, 24) + " => " + pad(out[0], 46) + " (" + ms + " ms)");
        }

        hdr("2. ReDoS VIA /pattern/ =~  -- does EVAL_TIMEOUT_MS=1000 stop it?");
        for (int n : new int[]{16, 20, 22, 24, 26}) {
            Map<String,Object> e2 = new HashMap<String,Object>();
            e2.put("s", repeat("a", n));
            String[] out = new String[1];
            long ms = bounded(A, "s =~ /^(a+)+b$/", e2, 4000, out);
            System.out.println("  n=" + pad(""+n, 3) + " " + pad(out[0], 40) + " (" + ms + " ms)");
        }
        System.out.println("  NOTE: java.util.regex runs inside ONE interpreter step; the timeout check cannot interleave.");

        hdr("3. ReDoS VIA string.replace_all / replace_first / split (String.* regex, UNVALIDATED)");
        for (String e : new String[]{
            "string.replace_all(s, /^(a+)+b$/, 'x')",
            "string.replace_all(s, '^(a+)+b$', 'x')",
            "string.replace_first(s, /^(a+)+b$/, 'x')",
            "string.split(s, /^(a+)+b$/)"}) {
            Map<String,Object> e2 = new HashMap<String,Object>();
            e2.put("s", repeat("a", 30));
            String[] out = new String[1];
            long ms = bounded(A, e, e2, 4000, out);
            System.out.println("  " + pad(e, 44) + " => " + pad(out[0], 38) + " (" + ms + " ms)");
        }

        hdr("4. REGEX CAPTURE GROUPS -> ENV MUTATION? (PUT_CAPTURING_GROUPS_INTO_ENV)");
        for (Boolean on : new Boolean[]{Boolean.TRUE, Boolean.FALSE}) {
            AviatorEvaluatorInstance G = AviatorEvaluator.newInstance();
            G.enableSandboxMode();
            G.setOption(Options.PUT_CAPTURING_GROUPS_INTO_ENV, on);
            java.util.Map<String,Object> e2 = new java.util.HashMap<String,Object>();
            e2.put("s", "abc123");
            String r;
            try { r = "OK match=" + G.execute("s =~ /([a-z]+)([0-9]+)/", e2) + "  envAfter=" + new TreeMap<String,Object>(e2); }
            catch (Throwable t) { r = "THROW:" + t.getClass().getSimpleName(); }
            System.out.println("  option=" + pad(""+on, 6) + " => " + r);
        }

        hdr("5. DOES DISABLING ENABLE_PROPERTY_SYNTAX_SUGAR CLOSE THE amount.class LEAK?");
        for (Boolean off : new Boolean[]{Boolean.FALSE, Boolean.TRUE}) {
            AviatorEvaluatorInstance P = AviatorEvaluator.newInstance();
            P.enableSandboxMode();
            P.setOption(Options.ENABLE_PROPERTY_SYNTAX_SUGAR, off);
            System.out.println("  --- ENABLE_PROPERTY_SYNTAX_SUGAR=" + off + " ---");
            for (String e : new String[]{"amount.class", "amount.class.name", "'a'.class"}) {
                try { System.out.println("    " + pad(e, 20) + " => OK:" + P.execute(e, env)); }
                catch (Throwable t) { System.out.println("    " + pad(e, 20) + " => THROW:" + t.getClass().getSimpleName()); }
            }
        }

        hdr("6. *** DECISIVE *** addOpFunction STEP COUNTER -- CONSULTED IN ASM MODE OR NOT?");
        for (EvalMode mode : new EvalMode[]{EvalMode.ASM, EvalMode.INTERPRETER}) {
            final AviatorEvaluatorInstance O = AviatorEvaluator.newInstance();
            O.enableSandboxMode();
            O.setOption(Options.EVAL_MODE, mode);
            final AtomicLong hits = new AtomicLong();
            final AtomicLong over = new AtomicLong();
            O.addOpFunction(OperatorType.ADD, new AbstractFunction() {
                public String getName() { return "stepCounter.add"; }
                public AviatorObject call(java.util.Map<String,Object> e, AviatorObject x, AviatorObject y) {
                    hits.incrementAndGet();
                    if (over.incrementAndGet() > 3) throw new ExpressionRuntimeException("EvaluationBudgetExceeded");
                    return AviatorLong.valueOf(((AviatorNumber)x).longValue() + ((AviatorNumber)y).longValue());
                }
            });
            O.addOpFunction(OperatorType.MULT, new AbstractFunction() {
                public String getName() { return "stepCounter.mul"; }
                public AviatorObject call(java.util.Map<String,Object> e, AviatorObject x, AviatorObject y) {
                    hits.incrementAndGet();
                    if (over.incrementAndGet() > 3) throw new ExpressionRuntimeException("EvaluationBudgetExceeded");
                    return AviatorLong.valueOf(((AviatorNumber)x).longValue() * ((AviatorNumber)y).longValue());
                }
            });
            String r;
            try { r = "OK:" + O.execute("amount > 1 ? 10+1 : 10+1+1+1+1+1+1+1+1+1", env); }
            catch (Throwable t) { r = "THROW:" + t.getClass().getSimpleName() + ":" + t.getMessage(); }
            System.out.println("  EVAL_MODE=" + pad(""+mode, 12) + " opHooksFired=" + hits.get()
                + "  exprResult=" + r);
            System.out.println("      => " + (hits.get() > 0
                ? "opsMap IS consulted in this mode (counter enforceable)"
                : "opsMap NOT consulted -- STEP COUNTER IS A SILENT NO-OP IN THIS MODE"));
        }

        hdr("7. CORRECTED CLASS/METASPACE COUNTS (warmed, N=20000, validate vs compile)");
        ClassLoadingMXBean cl = ManagementFactory.getClassLoadingMXBean();
        AviatorEvaluatorInstance V = AviatorEvaluator.newInstance();
        V.enableSandboxMode();
        String[] bad = {"amount > ", "(((((", "amount ++++ ", "'unterminated", "fn ( {"};
        for (int i=0;i<3000;i++) { try { V.validate(bad[i%bad.length] + " " + i); } catch (Throwable t) {} }
        for (int i=0;i<3000;i++) { try { V.compile("warm" + i + " > 1", false); } catch (Throwable t) {} }
        int N = 20000;
        long v0=cl.getTotalLoadedClassCount(), vm0=metaspaceMB();
        for (int i=0;i<N;i++) { try { V.validate(bad[i%bad.length] + " " + i); } catch (Throwable t) {} }
        long v1=cl.getTotalLoadedClassCount(), vm1=metaspaceMB();
        System.out.println("  N=" + N + " validate(malformed) -> classes +" + (v1-v0) + "   metaspace " + vm0 + "->" + vm1 + " MB");
        long c0=cl.getTotalLoadedClassCount(), cm0=metaspaceMB();
        for (int i=0;i<N;i++) { try { V.compile(bad[i%bad.length] + " " + i, false); } catch (Throwable t) {} }
        long c1=cl.getTotalLoadedClassCount(), cm1=metaspaceMB();
        System.out.println("  N=" + N + " compile(malformed)  -> classes +" + (c1-c0) + "   metaspace " + cm0 + "->" + cm1 + " MB");
        long g0=cl.getTotalLoadedClassCount(), gm0=metaspaceMB();
        for (int i=0;i<N;i++) { try { V.compile("amount > " + i, false); } catch (Throwable t) {} }
        long g1=cl.getTotalLoadedClassCount(), gm1=metaspaceMB();
        System.out.println("  N=" + N + " compile(valid)      -> classes +" + (g1-g0) + "   metaspace " + gm0 + "->" + gm1 + " MB");
        System.out.println("  => per malformed compile attempt: " + ((c1-c0)/(double)N) + " classes | per valid compile: " + ((g1-g0)/(double)N) + " classes");

        hdr("8. LRU BOUNDS THE CACHE, NOT THE GENERATED CLASSES");
        AviatorEvaluatorInstance C = AviatorEvaluator.newInstance();
        C.enableSandboxMode();
        C.useLRUExpressionCache(50);
        for (int i=0;i<500;i++) { try { C.execute("warm" + i + " > 1", env); } catch (Throwable t) {} }
        long l0=cl.getTotalLoadedClassCount(), lm0=metaspaceMB();
        for (int i=0;i<5000;i++) { try { C.execute("amount > " + i, env); } catch (Throwable t) {} }
        long l1=cl.getTotalLoadedClassCount(), lm1=metaspaceMB();
        System.out.println("  LRU(50) + 5000 distinct execute -> classes +" + (l1-l0) + "   metaspace " + lm0 + "->" + lm1 + " MB");

        hdr("9. SANDBOX READBACK (no isSandboxMode() API exists)");
        AviatorEvaluatorInstance V2 = AviatorEvaluator.newInstance();
        V2.enableSandboxMode();
        for (Options o : Options.values()) {
            Object v = V2.getOption(o);
            String s = (v instanceof Object[]) ? Arrays.toString((Object[])v) : String.valueOf(v);
            System.out.println("  " + pad(o.name(), 46) + " = " + s);
        }
        System.out.println("  sandbox proof = FEATURE_SET is narrow AND ALLOWED_CLASS_SET is empty");

        System.exit(0);
    }
}
