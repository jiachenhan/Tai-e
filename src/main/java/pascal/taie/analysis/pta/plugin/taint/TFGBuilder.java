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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.graph.flowgraph.ArrayIndexNode;
import pascal.taie.analysis.graph.flowgraph.FlowEdge;
import pascal.taie.analysis.graph.flowgraph.InstanceFieldNode;
import pascal.taie.analysis.graph.flowgraph.InstanceNode;
import pascal.taie.analysis.graph.flowgraph.Node;
import pascal.taie.analysis.graph.flowgraph.ObjectFlowGraph;
import pascal.taie.analysis.graph.flowgraph.VarNode;
import pascal.taie.analysis.pta.PointerAnalysisResult;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.ir.exp.Var;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.util.collection.MultiMap;
import pascal.taie.util.collection.Pair;
import pascal.taie.util.collection.Sets;
import pascal.taie.util.graph.Reachability;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

class TFGBuilder {

    private static final Logger logger = LogManager.getLogger(TFGBuilder.class);

    private final PointerAnalysisResult pta;

    private final ObjectFlowGraph ofg;

    private final MultiMap<Var, Pair<Var, Type>> varTransfers;

    private final Set<TaintFlow> taintFlows;

    private final TaintManager taintManager;

    /**
     * Whether only track taint flow in application code.
     */
    private final boolean onlyApp = true;

    /**
     * Whether only track taint flow that reaches any sink.
     */
    private final boolean onlyReachSink = true;

    TFGBuilder(PointerAnalysisResult pta,
               MultiMap<Var, Pair<Var, Type>> varTransfers,
               Set<TaintFlow> taintFlows,
               TaintManager taintManager) {
        this.pta = pta;
        this.ofg = pta.getObjectFlowGraph();
        this.varTransfers = varTransfers;
        this.taintFlows = taintFlows;
        this.taintManager = taintManager;
    }

    /**
     * Builds a complete taint flow graph.
     */
    private TaintFlowGraph buildComplete() {
        // collect source nodes
        Set<Node> sourceNodes = Sets.newHybridSet();
        taintManager.getTaintObjs()
                .stream()
                .map(taintManager::getSourcePoint)
                .forEach(p -> {
                    Var sourceVar = null;
                    if (p instanceof CallSourcePoint csp) {
                        sourceVar = InvokeUtils.getVar(
                                csp.sourceCall(), csp.index());
                    } else if (p instanceof ParamSourcePoint psp) {
                        sourceVar = psp.sourceMethod().getIR()
                                .getParam(psp.index());
                    }
                    if (sourceVar != null) {
                        sourceNodes.add(ofg.getVarNode(sourceVar));
                    }
                });
        logger.info("Source nodes:");
        sourceNodes.forEach(logger::info);
        // collect sink nodes
        Set<Node> sinkNodes = Sets.newHybridSet();
        taintFlows.forEach(taintFlow -> {
            SinkPoint sinkPoint = taintFlow.sinkPoint();
            Var sinkVar = InvokeUtils.getVar(sinkPoint.sinkCall(), sinkPoint.index());
            sinkNodes.add(ofg.getVarNode(sinkVar));
        });
        logger.info("Sink nodes:");
        sinkNodes.forEach(logger::info);
        // builds taint flow graph
        TaintFlowGraph tfg = new TaintFlowGraph(sourceNodes, sinkNodes);
        Set<Node> visitedNodes = Sets.newHybridSet();
        Deque<Node> workList = new ArrayDeque<>(sourceNodes);
        while (!workList.isEmpty()) {
            Node node = workList.poll();
            visitedNodes.add(node);
            getOutEdges(node).forEach(edge -> {
                Node target = edge.target();
                if (!onlyApp || isApp(target)) {
                    tfg.addEdge(edge);
                    if (!visitedNodes.contains(target)) {
                        workList.add(target);
                    }
                }
            });
        }
        return tfg;
    }

    private List<FlowEdge> getOutEdges(Node node) {
        List<FlowEdge> edges = new ArrayList<>();
        // collect OFG edges
        ofg.getOutEdgesOf(node).forEach(edge -> {
            switch (edge.kind()) {
                case LOCAL_ASSIGN, INSTANCE_STORE, ARRAY_STORE,
                        THIS_PASSING, PARAMETER_PASSING -> {
                    edges.add(edge);
                }
                case CAST, INSTANCE_LOAD, ARRAY_LOAD, RETURN -> {
                    // check whether target node also contains taint objects
                    // to filter spurious flow edges
                    for (Obj obj : getPointsToSet(edge.target())) {
                        if (taintManager.isTaint(obj)) {
                            edges.add(edge);
                            break;
                        }
                    }
                }
            }
        });
        // collect var transfer edges
        if (node instanceof VarNode sourceNode) {
            Var source = sourceNode.getVar();
            varTransfers.get(source).forEach(pair -> {
                VarNode targetNode = ofg.getVarNode(pair.first());
                edges.add(new TransferEdge(sourceNode, targetNode));
            });
        }
        return edges;
    }

    private Set<Obj> getPointsToSet(Node node) {
        if (node instanceof VarNode varNode) {
            return pta.getPointsToSet(varNode.getVar());
        } else if (node instanceof InstanceFieldNode ifNode) {
            return pta.getPointsToSet(ifNode.getBase(), ifNode.getField());
        } else if (node instanceof ArrayIndexNode aiNode) {
            return pta.getPointsToSet(aiNode.getBase());
        } else {
            return Set.of();
        }
    }

    TaintFlowGraph build() {
        TaintFlowGraph complete = buildComplete();
        Set<Node> sourceNodes = complete.getSourceNodes();
        Set<Node> sinkNodes = complete.getSinkNodes();
        TaintFlowGraph tfg = new TaintFlowGraph(sourceNodes, sinkNodes);
        Set<Node> nodesReachSink = null;
        if (onlyReachSink) {
            nodesReachSink = Sets.newHybridSet();
            Reachability<Node> reachability = new Reachability<>(complete);
            for (Node sink : sinkNodes) {
                nodesReachSink.addAll(reachability.nodesCanReach(sink));
            }
        }
        Set<Node> visitedNodes = Sets.newHybridSet();
        Deque<Node> workList = new ArrayDeque<>(complete.getSourceNodes());
        while (!workList.isEmpty()) {
            Node node = workList.poll();
            visitedNodes.add(node);
            for (FlowEdge edge : complete.getOutEdgesOf(node)) {
                Node target = edge.target();
                if (!onlyReachSink || nodesReachSink.contains(target)) {
                    tfg.addEdge(edge);
                    if (!visitedNodes.contains(target)) {
                        workList.add(target);
                    }
                }
            }
        }
        return tfg;
    }

    private static boolean isApp(Node node) {
        if (node instanceof VarNode varNode) {
            return varNode.getVar().getMethod().isApplication();
        } else if (node instanceof InstanceNode iNode) {
            return iNode.getBase().getContainerMethod()
                    .stream()
                    .anyMatch(JMethod::isApplication);
        } else {
            return false;
        }
    }
}
