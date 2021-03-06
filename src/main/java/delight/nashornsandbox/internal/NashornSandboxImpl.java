package delight.nashornsandbox.internal;

import java.io.Writer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import jdk.nashorn.api.scripting.NashornScriptEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import delight.nashornsandbox.NashornSandbox;
import delight.nashornsandbox.SecuredJsCache;
import delight.nashornsandbox.exceptions.ScriptCPUAbuseException;
import delight.nashornsandbox.exceptions.ScriptMemoryAbuseException;
import jdk.nashorn.api.scripting.NashornScriptEngineFactory;

/**
 * Nashorn sandbox implementation.
 *
 * <p>
 * Created on 2015-08-07
 * </p>
 *
 * @author <a href="mailto:mxro@nowhere.com">mxro</a>
 * @author <a href="mailto:mellster2000@yahoo.com">Marco Ellwanger</a>
 * @author <a href="mailto:dev@youness.org">Youness SAHOUANE</a>
 * @author <a href="mailto:eduveks@gmail.com">Eduardo Velasques</a>
 * @author <a href="mailto:philip.borgstrom@gmail.com">philipborg</a>
 * @author <a href="mailto:marcin.golebski@verbis.pl">Marcin Golebski</a>
 * @version $Id$
 */
@SuppressWarnings("restriction")
public class NashornSandboxImpl implements NashornSandbox {

	static final Logger LOG = LoggerFactory.getLogger(NashornSandbox.class);

	protected final SandboxClassFilter sandboxClassFilter = new SandboxClassFilter();

	protected final ScriptEngine scriptEngine;

	/** Maximum CPU time in milliseconds. */
	protected long maxCPUTime = 0L;

	/** Maximum memory of executor thread used. */
	protected long maxMemory = 0L;

	protected ExecutorService executor;

	protected boolean allowPrintFunctions = false;

	protected boolean allowReadFunctions = false;

	protected boolean allowLoadFunctions = false;

	protected boolean allowExitFunctions = false;

	protected boolean allowGlobalsObjects = false;

	protected boolean allowNoBraces = false;

	protected JsEvaluator evaluator;

	protected JsSanitizer sanitizer;

    protected boolean engineAsserted;

	protected Invocable lazyInvocable;

	/** The size of the LRU cache of prepared statements. */
	protected int maxPreparedStatements;

	protected SecuredJsCache suppliedCache;

	protected Bindings cached;

	public NashornSandboxImpl() {
		this(new String[0]);
	}

	public NashornSandboxImpl(String... params) {
		this(null, params);
	}

	public NashornSandboxImpl(ScriptEngine engine, String... params) {
		for (String param : params) {
			if (param.equals("--no-java")) {
				throw new IllegalArgumentException(
						"The engine parameter --no-java is not supported. Using it would interfere with the injected code to test for infinite loops.");
			}
		}
		this.scriptEngine = engine == null
                        ? new NashornScriptEngineFactory().getScriptEngine(params, this.getClass().getClassLoader(),
                                this.sandboxClassFilter)
                        : engine;
		this.maxPreparedStatements = 0;
		this.allow(InterruptTest.class);
        this.engineAsserted = false;
	}

	private synchronized void assertScriptEngine() {
		try {
            if (!engineAsserted) {
				produceSecureBindings();
			} else if (!engineBindingUnchanged()) {
				resetEngineBindings();
			}
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	// This will detect whether the current engine bindings match the cached
	// protected bindings
	private boolean engineBindingUnchanged() {
		final Bindings current = scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE);
		for (Map.Entry<String, Object> e : cached.entrySet()) {
			if (!current.containsKey(e.getKey()) || !Objects.equals(current.get(e.getKey()), e.getValue())) {
				return false;
			}
		}
		return true;
	}

	private void produceSecureBindings() {
		try {
			final StringBuilder sb = new StringBuilder();
			cached = scriptEngine.getBindings(ScriptContext.ENGINE_SCOPE);
			sanitizeBindings(cached);
			if (!allowExitFunctions) {
				sb.append("var quit=function(){};var exit=function(){};");
			}
			if (!allowPrintFunctions) {
				sb.append("var print=function(){};var echo = function(){};");
			}
			if (!allowReadFunctions) {
				sb.append("var readFully=function(){};").append("var readLine=function(){};");
			}
			if (!allowLoadFunctions) {
				sb.append("var load=function(){};var loadWithNewGlobal=function(){};");
			}
			if (!allowGlobalsObjects) {
				// Max 22nd of Feb 2018: I don't think these are strictly necessary since they
				// are only available in scripting mode
				sb.append("var $ARG=null;var $ENV=null;var $EXEC=null;");
				sb.append("var $OPTIONS=null;var $OUT=null;var $ERR=null;var $EXIT=null;");
			}
			scriptEngine.eval(sb.toString());

			resetEngineBindings();

            engineAsserted=true;

		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	protected void resetEngineBindings() {
		final Bindings bindings = createBindings();
		sanitizeBindings(bindings);
		bindings.putAll(cached);
		scriptEngine.setBindings(bindings, ScriptContext.ENGINE_SCOPE);
	}

	protected void sanitizeBindings(Bindings bindings) {
		if (!allowExitFunctions) {
			bindings.remove("quit");
			bindings.remove("exit");
		}
		if (!allowPrintFunctions) {
			bindings.remove("print");
			bindings.remove("echo");
		}
		if (!allowReadFunctions) {
			bindings.remove("readFully");
			bindings.remove("readLine");
		}
		if (!allowLoadFunctions) {
			bindings.remove("load");
			bindings.remove("loadWithNewGlobal");
		}
	}

	@Override
	public Object eval(final String js) throws ScriptCPUAbuseException, ScriptException {
		return eval(js, null, null);
	}

	@Override
	public Object eval(String js, Bindings bindings) throws ScriptCPUAbuseException, ScriptException {
		return eval(js, null, bindings);
	}

	@Override
	public Object eval(String js, ScriptContext scriptContext) throws ScriptCPUAbuseException, ScriptException {
		return eval(js, scriptContext, null);
	}

	@Override
	public Object eval(final String js, final ScriptContext scriptContext, final Bindings bindings)
			throws ScriptCPUAbuseException, ScriptException {
		assertScriptEngine();
		final JsSanitizer sanitizer = getSanitizer();
		// see https://github.com/javadelight/delight-nashorn-sandbox/issues/73
		final String blockAccessToEngine = "Object.defineProperty(this, 'engine', {});"
				+ "Object.defineProperty(this, 'context', {});delete this.__noSuchProperty__;";
		final String securedJs;
		if (scriptContext == null) {
			securedJs = blockAccessToEngine + sanitizer.secureJs(js);
		} else {
			// Unfortunately, blocking access to the engine property inteferes with setting
			// a script context
			// needs further investigation
			securedJs = sanitizer.secureJs(js);
		}
		final Bindings securedBindings = secureBindings(bindings);
		EvaluateOperation op = new EvaluateOperation(securedJs, scriptContext, securedBindings);
		return executeSandboxedOperationAsserted(op);
	}

	protected Bindings secureBindings(Bindings bindings) {
		if (bindings == null)
			return null;

		bindings.putAll(cached);

		return bindings;
	}




    @Override
    public CompiledScript compile(final String js) throws ScriptException {
        if (!engineAsserted) {
            assertScriptEngine();
        }

        final JsSanitizer sanitizer = getSanitizer();
        final String securedJs = sanitizer.secureJs(js);

        if (LOG.isDebugEnabled()) {
            LOG.debug("--- Compiling JS ---");
            LOG.debug(securedJs);
            LOG.debug("--- JS END ---");
        }
        return ((NashornScriptEngine)scriptEngine).compile(securedJs);
    }

//    public CompiledScript compilePrivileged(final String js) throws ScriptException {
//
//        final JsSanitizer sanitizer = getSanitizer();
//        final String securedJs = sanitizer.secureJs(js);
//
//        if (LOG.isDebugEnabled()) {
//            LOG.debug("--- Compiling P JS ---");
//            LOG.debug(securedJs);
//            LOG.debug("--- JS END ---");
//        }
//        return ((NashornScriptEngine)scriptEngine).compile(securedJs);
//    }

    @Override
    public Object evalCompiled(final CompiledScript cs) throws ScriptException {
        return evalCompiled(cs, null, null);
    }

    @Override
    public Object evalCompiled(final CompiledScript cs, final Bindings bindings) throws ScriptCPUAbuseException, ScriptException {
        return evalCompiled(cs,null, bindings);
    }

    @Override
    public Object evalCompiled(final CompiledScript cs, final ScriptContext scriptContext) throws ScriptCPUAbuseException, ScriptException {
        return evalCompiled(cs, scriptContext,null);
    }

    @Override
    public Object evalCompiled(final CompiledScript cs, final ScriptContext scriptContext, final Bindings bindings)
        throws ScriptException {

        if (!engineAsserted) {
            assertScriptEngine();
        }

        final ScriptEngineOperation op = new EvaluateCompiledOperation(cs, scriptContext,bindings);
        return executeSandboxedOperationAsserted(op);
    }

//    public Object evalCompiledPrivileged(final CompiledScript cs, final ScriptContext scriptContext, final Bindings bindings)
//        throws ScriptException {
//
//        final ScriptEngineOperation op = new EvaluateCompiledOperation(cs, scriptContext,bindings);
//        return executeSandboxedOperationUnasserted(op);
//    }

    protected Object executeSandboxedOperationAsserted(ScriptEngineOperation op) throws ScriptCPUAbuseException, ScriptException {
        if (!engineAsserted) {
            assertScriptEngine();
        }
        return executeSandboxedOperationUnasserted(op);
    }

    protected Object executeSandboxedOperationUnasserted(ScriptEngineOperation op)
			throws ScriptCPUAbuseException, ScriptException {
		try {
			if (maxCPUTime == 0 && maxMemory == 0) {
				return op.executeScriptEngineOperation(scriptEngine);
			}
			checkExecutorPresence();
			final JsEvaluator evaluator = getEvaluator(op);
			executor.execute(evaluator);
			evaluator.runMonitor();
			if (evaluator.isCPULimitExceeded()) {
				throw new ScriptCPUAbuseException(
						"Script used more than the allowed [" + maxCPUTime + " ms] of CPU time.",
						evaluator.isScriptKilled(), evaluator.getException());
			} else if (evaluator.isMemoryLimitExceeded()) {
				throw new ScriptMemoryAbuseException(
						"Script used more than the allowed [" + maxMemory + " B] of memory.",
						evaluator.isScriptKilled(), evaluator.getException());
			}
			if (evaluator.getException() != null) {
				throw evaluator.getException();
			}
			return evaluator.getResult();
		} catch (RuntimeException | ScriptException e) {
			throw e;
		} catch (final Exception e) {
			throw new RuntimeException(e);
		}
	}

	private JsEvaluator getEvaluator(ScriptEngineOperation op) {
		return new JsEvaluator(scriptEngine, maxCPUTime, maxMemory, op);
	}

	private void checkExecutorPresence() {
		if (executor == null) {
			throw new IllegalStateException("When a CPU time or memory limit is set, an executor "
					+ "needs to be provided by calling .setExecutor(...)");
		}
	}

	@Override
	public void setMaxCPUTime(final long limit) {
		maxCPUTime = limit;
	}

	@Override
	public void setMaxMemory(final long limit) {
		maxMemory = limit;
	}

	protected JsSanitizer getSanitizer() {
		if (sanitizer == null) {
			if (suppliedCache == null) {
				sanitizer = new JsSanitizer(scriptEngine, maxPreparedStatements, allowNoBraces);
			} else {
				sanitizer = new JsSanitizer(scriptEngine, allowNoBraces, suppliedCache);
			}
		}
		return sanitizer;
	}

	@Override
	public void allow(final Class<?> clazz) {
		sandboxClassFilter.add(clazz);
	}

	@Override
	public void disallow(final Class<?> clazz) {
		sandboxClassFilter.remove(clazz);
	}

	@Override
	public boolean isAllowed(final Class<?> clazz) {
		return sandboxClassFilter.contains(clazz);
	}

	@Override
	public void disallowAllClasses() {
		sandboxClassFilter.clear();
		// this class must always be allowed, see issue 54
		// https://github.com/javadelight/delight-nashorn-sandbox/issues/54
		allow(InterruptTest.class);
	}

	@Override
	public void inject(final String variableName, final Object object) {
		if (object != null && !sandboxClassFilter.contains(object.getClass())) {
			allow(object.getClass());
		}
		scriptEngine.put(variableName, object);
	}

	@Override
	public void setExecutor(final ExecutorService executor) {
		this.executor = executor;
	}

	@Override
	public ExecutorService getExecutor() {
		return executor;
	}

	@Override
	public Object get(final String variableName) {
		return scriptEngine.get(variableName);
	}

	@Override
	public void allowPrintFunctions(final boolean v) {
        if (engineAsserted) {
			throw new IllegalStateException("Please set this property before calling eval.");
		}
		allowPrintFunctions = v;
	}

	@Override
	public void allowReadFunctions(final boolean v) {
        if (engineAsserted) {
			throw new IllegalStateException("Please set this property before calling eval.");
		}
		allowReadFunctions = v;
	}

	@Override
	public void allowLoadFunctions(final boolean v) {
        if (engineAsserted) {
			throw new IllegalStateException("Please set this property before calling eval.");
		}
		allowLoadFunctions = v;
	}

	@Override
	public void allowExitFunctions(final boolean v) {
        if (engineAsserted) {
			throw new IllegalStateException("Please set this property before calling eval.");
		}
		allowExitFunctions = v;
	}

	@Override
	public void allowGlobalsObjects(final boolean v) {
        if (engineAsserted) {
			throw new IllegalStateException("Please set this property before calling eval.");
		}
		allowGlobalsObjects = v;
	}

	@Override
	public void allowNoBraces(final boolean v) {
		if (allowNoBraces != v) {
			sanitizer = null;
		}
		allowNoBraces = v;
	}

	@Override
	public void setWriter(final Writer writer) {
		scriptEngine.getContext().setWriter(writer);
	}

	@Override
	public void setMaxPreparedStatements(final int max) {
		if (maxPreparedStatements != max) {
			sanitizer = null;
		}
		maxPreparedStatements = max;
	}

	@Override
	public Bindings createBindings() {
		return scriptEngine.createBindings();
	}

	@Override
	public Invocable getSandboxedInvocable() {
		if (maxMemory == 0 && maxCPUTime == 0) {
			return (Invocable) scriptEngine;
		}
		return getLazySandboxedInvocable();
	}

	private Invocable getLazySandboxedInvocable() {
		if (lazyInvocable == null) {
			Invocable sandboxInvocable = new Invocable() {

				@Override
				public Object invokeMethod(Object thiz, String name, Object... args)
						throws ScriptException, NoSuchMethodException {
					InvokeOperation op = new InvokeOperation(thiz, name, args);
					try {
						return executeSandboxedOperationAsserted(op);
					} catch (ScriptException e) {
						throw e;
					} catch (Exception e) {
						throw new ScriptException(e);
					}
				}

				@Override
				public Object invokeFunction(String name, Object... args)
						throws ScriptException, NoSuchMethodException {
					InvokeOperation op = new InvokeOperation(null, name, args);
					try {
						return executeSandboxedOperationAsserted(op);
					} catch (ScriptException e) {
						throw e;
					} catch (Exception e) {
						throw new ScriptException(e);
					}
				}

				@Override
				public <T> T getInterface(Object thiz, Class<T> clasz) {
					// TODO add proxy wrapper for proper sandboxing
					throw new IllegalStateException("Not yet implemented");
				}

				@Override
				public <T> T getInterface(Class<T> clasz) {
					// TODO add proxy wrapper for proper sandboxing
					throw new IllegalStateException("Not yet implemented");
				}
			};
			lazyInvocable = sandboxInvocable;
		}
		return lazyInvocable;
	}

	@Override
	public void setScriptCache(SecuredJsCache cache) {
		this.suppliedCache = cache;
	}
}
