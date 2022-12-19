/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2022 Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2022 Yue Li <yueli@nju.edu.cn>
 *
 * This file is part of Tai-e.
 *
 * Tai-e is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Tai-e is distributed in the hope that it will be useful,but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General
 * Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Tai-e. If not, see <https://www.gnu.org/licenses/>.
 */

package pascal.taie.analysis.pta.plugin.taint;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.InvokeExp;
import pascal.taie.ir.exp.InvokeInstanceExp;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static java.lang.System.out;

public class TaintAnalysis implements Plugin {

    private static final Logger logger = LogManager.getLogger(TaintAnalysis.class);

    /**
     * Map from method (which is source method) to set of types of
     * taint objects returned by the method calls.
     */
    private final MultiMap<JMethod, Type> sources = Maps.newMultiMap();

    /**
     * Map from method (which causes taint transfer) to set of relevant
     * {@link TaintTransfer}.
     */
    private final MultiMap<JMethod, TaintTransfer> transfers = Maps.newMultiMap();

    /**
     * Map from variable to taint transfer information.
     * The taint objects pointed to by the "key" variable are supposed
     * to be transferred to "value" variable with specified type.
     */
    private final MultiMap<Var, Pair<Var, Type>> varTransfers = Maps.newMultiMap();

    private Solver solver;

    private CSManager csManager;

    private Context emptyContext;

    private TaintManager manager;

    private TaintConfig config;

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        csManager = solver.getCSManager();
        emptyContext = solver.getContextSelector().getEmptyContext();
        manager = new TaintManager(solver.getHeapModel());
        config = TaintConfig.readConfig(
                solver.getOptions().getString("taint-config"),
                solver.getHierarchy(),
                solver.getTypeSystem());
        logger.info(config);
        config.getSources().forEach(s ->
                sources.put(s.method(), s.type()));
        config.getTransfers().forEach(t ->
                transfers.put(t.method(), t));
    }

    @Override
    public void onNewCallEdge(Edge<CSCallSite, CSMethod> edge) {
        Invoke callSite = edge.getCallSite().getCallSite();
        JMethod callee = edge.getCallee().getMethod();
        // generate taint value from source call
        Var lhs = callSite.getLValue();
        if (lhs != null && sources.containsKey(callee)) {
            sources.get(callee).forEach(type -> {
                Obj taint = manager.makeTaint(callSite, type);
                solver.addVarPointsTo(edge.getCallSite().getContext(), lhs,
                        emptyContext, taint);
            });
        }
        // process taint transfer
        transfers.get(callee).forEach(transfer -> {
            Var from = getVar(callSite, transfer.from());
            Var to = getVar(callSite, transfer.to());
            // when transfer to result variable, and the call site
            // does not have result variable, then "to" is null.
            if (to != null) {
                Type type = transfer.type();
                varTransfers.put(from, new Pair<>(to, type));
                Context ctx = edge.getCallSite().getContext();
                CSVar csFrom = csManager.getCSVar(ctx, from);
                transferTaint(solver.getPointsToSetOf(csFrom), ctx, to, type);
            }
        });
    }

    /**
     * Retrieves variable from a call site and index.
     */
    private static Var getVar(Invoke callSite, int index) {
        InvokeExp invokeExp = callSite.getInvokeExp();
        return switch (index) {
            case TaintTransfer.BASE -> ((InvokeInstanceExp) invokeExp).getBase();
            case TaintTransfer.RESULT -> callSite.getResult();
            default -> invokeExp.getArg(index);
        };
    }

    private void transferTaint(PointsToSet pts, Context ctx, Var to, Type type) {
        PointsToSet newTaints = solver.makePointsToSet();
        pts.objects()
                .map(CSObj::getObject)
                .filter(manager::isTaint)
                .map(manager::getSourceCall)
                .map(source -> manager.makeTaint(source, type))
                .map(taint -> csManager.getCSObj(emptyContext, taint))
                .forEach(newTaints::addObject);
        if (!newTaints.isEmpty()) {
            solver.addVarPointsTo(ctx, to, newTaints);
        }
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        varTransfers.get(csVar.getVar()).forEach(p -> {
            Var to = p.first();
            Type type = p.second();
            transferTaint(pts, csVar.getContext(), to, type);
        });
    }

    @Override
    public void onFinish() {
        Set<TaintFlow> taintFlows = collectTaintFlows();
        solver.getResult().storeResult(getClass().getName(), taintFlows);
    }

    private Set<TaintFlow> collectTaintFlows() {
        PointerAnalysisResult result = solver.getResult();
        Set<TaintFlow> taintFlows = new TreeSet<>();
        config.getSinks().forEach(sink -> {
            int i = sink.index();
            result.getCallGraph()
                    .getCallersOf(sink.method())
                    .forEach(sinkCall -> {
                        Var arg = sinkCall.getInvokeExp().getArg(i);
                        result.getPointsToSet(arg)
                                .stream()
                                .filter(manager::isTaint)
                                .map(manager::getSourceCall)
                                .map(sourceCall -> new TaintFlow(sourceCall, sinkCall, i))
                                .forEach(taintFlows::add);
                    });
        });


        CallGraph<Invoke, JMethod> callGraph = result.getCallGraph();
        for (JMethod method : callGraph) {
            if (method.isNative())
            {
                out.printf("*****************************************************************\n");
                out.printf("*****************************************************************\n");
                callGraph.getCallersOf(method).forEach(nativeCaller -> {
                    out.printf("Native method : %s\n", method.getName());
                    out.printf("RetType : %s\n", method.getReturnType().toString());

                    nativeMethodTaintSummary nmts = new nativeMethodTaintSummary();
                    JsonToFile jf = new JsonToFile();
                    List<String> argsType = new ArrayList<>();
                    List<Integer> taintedArgsPos = new ArrayList<>();

                    for (int argPos = 0; argPos < nativeCaller.getInvokeExp().getArgCount(); ++argPos)
                    {
                        Var arg = nativeCaller.getInvokeExp().getArg(argPos);
                        out.printf("%dth argType : %s\n", argPos, arg.getType().toString());
                        argsType.add(arg.getType().toString());
                        for (Obj obj : result.getPointsToSet(arg))
                        {
                            if (manager.isTaint(obj))
                            {
                                out.printf("TaintedArg : %d\n", argPos);
                                taintedArgsPos.add(argPos);
                                break;
                            }
                        }
                    }

                    nmts.nativeMethodName = method.getName();
                    nmts.retType = method.getReturnType().toString();
                    nmts.argsType = argsType;
                    nmts.taintedArgsPos = taintedArgsPos;

                    try {
                        jf.jsonFormString(nmts);
                    } catch (JsonProcessingException e) {
                        throw new RuntimeException(e);
                    }

                });

                out.printf("*****************************************************************\n");
                out.printf("*****************************************************************\n");
            }
        }

        return taintFlows;
    }
}
