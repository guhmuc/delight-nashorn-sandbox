package delight.nashornsandbox.internal;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import static delight.nashornsandbox.internal.NashornSandboxImpl.LOG;

public class EvaluateCompiledOperation implements ScriptEngineOperation {

    private final CompiledScript cs;
    private final ScriptContext scriptContext;
    private final Bindings bindings;

    public CompiledScript getCompiledScript() {
        return cs;
    }

    public ScriptContext getScriptContext() {
        return scriptContext;
    }

    public Bindings getBindings() {
        return bindings;
    }

    public EvaluateCompiledOperation(CompiledScript cs, ScriptContext scriptContext, Bindings bindings) {
        this.cs = cs;
        this.scriptContext = scriptContext;
        this.bindings = bindings;
    }

    @Override
    public Object executeScriptEngineOperation(ScriptEngine scriptEngine) throws ScriptException {
        if (bindings != null) {
            return cs.eval(bindings);
        } else if (scriptContext != null) {
            return cs.eval(scriptContext);
        } else {
            return cs.eval();
        }
    }
}
