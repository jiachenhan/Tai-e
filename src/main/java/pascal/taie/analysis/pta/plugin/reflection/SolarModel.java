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

package pascal.taie.analysis.pta.plugin.reflection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.ArrayIndex;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Descriptor;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.util.CSObjs;
import pascal.taie.analysis.pta.plugin.util.InvokeHandler;
import pascal.taie.analysis.pta.plugin.util.InvokeUtils;
import pascal.taie.analysis.pta.plugin.util.Reflections;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.exp.CastExp;
import pascal.taie.ir.exp.NullLiteral;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.stmt.Cast;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.language.classes.ClassNames;
import pascal.taie.language.classes.JClass;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.ClassType;
import pascal.taie.util.collection.Maps;
import pascal.taie.util.collection.MultiMap;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static pascal.taie.analysis.pta.plugin.util.InvokeUtils.BASE;

/**
 * Implementation of Solar, a powerful static reflection analysis.
 * The technique was presented in paper:
 * Yue Li, Tian Tan, and Jingling Xue.
 * Understanding and Analyzing Java Reflection.
 * In TOSEM 2019.
 */
public class SolarModel extends InferenceModel {

    private static final Logger logger = LogManager.getLogger(SolarModel.class);

    /**
     * Descriptor for the unknown objects generated by reflective invocation.
     */
    private static final Descriptor UNKNOWN_DESC = () -> "UnknownReflectiveObj";

    /**
     * Only use type information in application code to infer reflective calls.
     */
    private static final boolean ONLY_APP = true;

    private final TypeMatcher typeMatcher;

    private final ClassType object;

    /**
     * Maps from variable to types it is cast to.
     */
    private final MultiMap<Var, ClassType> casts = Maps.newMultiMap();

    /**
     * Set of reflective invocations that are not soundly resolved by Solar.
     */
    private final Set<Invoke> unsoundInvokes = new TreeSet<>();

    SolarModel(Solver solver, MetaObjHelper helper,
               TypeMatcher typeMatcher, Set<Invoke> invokesWithLog) {
        super(solver, helper, invokesWithLog);
        this.typeMatcher = typeMatcher;
        object = typeSystem.getClassType(ClassNames.OBJECT);
    }

    private boolean isIgnored(Invoke invoke) {
        return invokesWithLog.contains(invoke) ||
                (ONLY_APP && !invoke.getContainer().isApplication());
    }

    // ---------- Implementation of rules for propagation (starts) ----------
    @InvokeHandler(signature = "<java.lang.Class: java.lang.Class forName(java.lang.String)>", argIndexes = {0})
    @InvokeHandler(signature = "<java.lang.Class: java.lang.Class forName(java.lang.String,boolean,java.lang.ClassLoader)>", argIndexes = {0})
    public void classForName(CSVar csVar, PointsToSet pts, Invoke invoke) {
        if (isIgnored(invoke)) {
            return;
        }
        Context context = csVar.getContext();
        pts.forEach(obj -> {
            if (!heapModel.isStringConstant(obj.getObject())) { // generate c^u
                Var result = invoke.getResult();
                if (result != null) {
                    Obj unknownClass = helper.getUnknownClass(invoke);
                    solver.addVarPointsTo(context, result, unknownClass);
                }
            } else { // generate c^t
                classForNameKnown(context, invoke, CSObjs.toString(obj));
            }
        });
    }

    @InvokeHandler(signature = "<java.lang.Class: java.lang.reflect.Constructor getConstructor(java.lang.Class[])>", argIndexes = {BASE})
    @InvokeHandler(signature = "<java.lang.Class: java.lang.reflect.Constructor getDeclaredConstructor(java.lang.Class[])>", argIndexes = {BASE})
    public void classGetConstructor(CSVar csVar, PointsToSet pts, Invoke invoke) {
        if (invokesWithLog.contains(invoke)) {
            return;
        }
        Context context = csVar.getContext();
        pts.forEach(obj -> classGetConstructorKnown(context, invoke, CSObjs.toClass(obj)));
    }

    @InvokeHandler(signature = "<java.lang.Class: java.lang.reflect.Method getMethod(java.lang.String,java.lang.Class[])>", argIndexes = {BASE, 0})
    @InvokeHandler(signature = "<java.lang.Class: java.lang.reflect.Method getDeclaredMethod(java.lang.String,java.lang.Class[])>", argIndexes = {BASE, 0})
    public void classGetMethod(CSVar csVar, PointsToSet pts, Invoke invoke) {
        if (isIgnored(invoke)) {
            return;
        }
        Var result = invoke.getResult();
        if (result != null) {
            List<PointsToSet> args = getArgs(csVar, pts, invoke, BASE, 0);
            PointsToSet classObjs = args.get(0);
            PointsToSet nameObjs = args.get(1);
            Context context = csVar.getContext();
            classObjs.forEach(classObj -> {
                boolean isClassUnknown = helper.isUnknownMetaObj(classObj);
                JClass clazz = CSObjs.toClass(classObj);
                nameObjs.forEach(nameObj -> {
                    boolean isNameUnknown = !heapModel.isStringConstant(
                            nameObj.getObject());
                    String name = CSObjs.toString(nameObj);
                    if (isClassUnknown || isNameUnknown) { // generate m^t_u, m^u_s, and m^u_u
                        Obj unknownMethod = helper.getUnknownMethod(invoke, clazz, name);
                        solver.addVarPointsTo(context, result, unknownMethod);
                    } else { // generate m^t_s
                        classGetMethodKnown(context, invoke, clazz, name);
                    }
                });
            });
        }
    }

    @InvokeHandler(signature = "<java.lang.Class: java.lang.reflect.Method[] getMethods()>", argIndexes = {BASE})
    @InvokeHandler(signature = "<java.lang.Class: java.lang.reflect.Method[] getDeclaredMethods()>", argIndexes = {BASE})
    public void classGetMethods(CSVar csVar, PointsToSet pts, Invoke invoke) {
        if (isIgnored(invoke)) {
            return;
        }
        Var result = invoke.getResult();
        if (result != null) {
            Context context = csVar.getContext();
            CSObj methodArray = csManager.getCSObj(context, helper.getMetaObjArray(invoke));
            ArrayIndex methodArrayIndex = csManager.getArrayIndex(methodArray);
            pts.forEach(classObj -> {
                Obj method;
                if (helper.isUnknownMetaObj(classObj)) { // generate m^u_u
                    method = helper.getUnknownMethod(invoke, null, null);
                } else { // generate m^t_u
                    JClass clazz = CSObjs.toClass(classObj);
                    method = helper.getUnknownMethod(invoke, clazz, null);
                }
                solver.addPointsTo(methodArrayIndex, method);
                solver.addVarPointsTo(context, result, methodArray);
            });
        }
    }
    // ---------- Implementation of rules for propagation (ends) ----------

    // ---------- Implementation of rules for collective inference (starts) ----------
    @InvokeHandler(signature = "<java.lang.reflect.Method: java.lang.Object invoke(java.lang.Object,java.lang.Object[])>", argIndexes = {BASE, 0})
    public void methodInvoke(CSVar csVar, PointsToSet pts, Invoke invoke) {
        if (isIgnored(invoke)) {
            return;
        }
        List<PointsToSet> args = getArgs(csVar, pts, invoke, BASE, 0);
        PointsToSet mtdObjs = args.get(0);
        // infer m^t_s from m^t_u (obj) with type information at invoke
        if (typeMatcher.hasTypeInfo(invoke)) {
            Context context = csVar.getContext();
            Var m = InvokeUtils.getVar(invoke, BASE); // m.invoke(o, args);
            mtdObjs.forEach(obj -> {
                if (helper.isUnknownMetaObj(obj)) {
                    MethodInfo methodInfo = helper.getMethodInfo(obj);
                    JClass clazz = methodInfo.clazz();
                    if (clazz != null && (!ONLY_APP || clazz.isApplication())) {
                        // class is known in methodInfo
                        Stream<JMethod> targets = methodInfo.isFromGetMethod()
                                ? Reflections.getMethods(clazz)
                                : Reflections.getDeclaredMethods(clazz);
                        targets.filter(target -> !typeMatcher.isUnmatched(invoke, target))
                                .map(helper::getMetaObj)
                                .forEach(mtdObj -> solver.addVarPointsTo(context, m, mtdObj));
                    }
                }
            });
        }
        // collect unsound Method.invoke() call
        if (!unsoundInvokes.contains(invoke)) {
            PointsToSet recvObjs = args.get(1);
            Var o = InvokeUtils.getVar(invoke, 0);
            boolean oIsNull = o.isConst() && o.getConstValue() instanceof NullLiteral;
            for (CSObj mtdObj : mtdObjs) {
                if (helper.isUnknownMetaObj(mtdObj)) {
                    MethodInfo methodInfo = helper.getMethodInfo(mtdObj);
                    if (methodInfo.isClassUnknown()) {
                        if (oIsNull) {
                            unsoundInvokes.add(invoke);
                            return;
                        }
                        for (CSObj recvObj : recvObjs) {
                            if (recvObj.getObject() instanceof MockObj mockObj &&
                                    mockObj.getDescriptor().equals(UNKNOWN_DESC)) {
                                unsoundInvokes.add(invoke);
                                return;
                            }
                        }
                    }
                }
            }
        }
    }
    // ---------- Implementation of rules for collective inference (ends) ----------

    // ---------- Implementation of rules for lazy heap modeling (starts) ----------
    @InvokeHandler(signature = "<java.lang.Class: java.lang.Object newInstance()>", argIndexes = {BASE})
    public void classNewInstance(CSVar csVar, PointsToSet pts, Invoke invoke) {
        if (isIgnored(invoke)) {
            return;
        }
        Var result = invoke.getResult();
        if (result != null) {
            Context context = csVar.getContext();
            for (CSObj obj : pts) {
                if (helper.isUnknownMetaObj(obj)) {
                    CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
                    Obj unknownObj = heapModel.getMockObj(UNKNOWN_DESC,
                            csCallSite, object, invoke.getContainer(), false);
                    solver.addVarPointsTo(context, result, unknownObj);
                    return;
                }
            }
        }
    }

    @Override
    protected void handleNewNonInvokeStmt(Stmt stmt) {
        if (stmt instanceof Cast cast) { // record cast statements
            CastExp castExp = cast.getRValue();
            Var rhs = castExp.getValue();
            if (castExp.getCastType() instanceof ClassType type &&
                    (!ONLY_APP || (rhs.getMethod().isApplication() &&
                            type.getJClass().isApplication()))) {
                casts.put(rhs, type);
            }
        }
    }

    @Override
    public boolean isRelevantVar(Var var) {
        return super.isRelevantVar(var) || casts.containsKey(var);
    }

    @Override
    public void handleNewPointsToSet(CSVar csVar, PointsToSet pts) {
        super.handleNewPointsToSet(csVar, pts);
        Set<ClassType> types = casts.get(csVar.getVar());
        if (!types.isEmpty()) {
            pts.forEach(obj -> {
                if (obj.getObject() instanceof MockObj mockObj &&
                        mockObj.getDescriptor().equals(UNKNOWN_DESC)) {
                    // unknown object flows to cast, use cast type
                    // to resolve the reflective call
                    CSCallSite csCallSite = (CSCallSite) mockObj.getAllocation();
                    Context context = csCallSite.getContext();
                    Var base = InvokeUtils.getVar(csCallSite.getCallSite(), BASE);
                    types.stream()
                            .map(ClassType::getJClass)
                            .map(hierarchy::getAllSubclassesOf)
                            .flatMap(Collection::stream)
                            .filter(c -> !c.isAbstract())
                            .map(helper::getMetaObj)
                            .forEach(classObj -> solver.addVarPointsTo(context, base, classObj));
                }
            });
        }
    }
    // ---------- Implementation of rules for lazy heap modeling (ends) ----------

    // ---------- Implementation of annotation guidance (starts) ----------
    @InvokeHandler(signature = "<java.lang.reflect.Array: java.lang.Object newInstance(java.lang.Class,int)>", argIndexes = {0})
    public void collectUnsoundArrayNewInstance(CSVar csVar, PointsToSet pts, Invoke invoke) {
        if (isIgnored(invoke) || unsoundInvokes.contains(invoke)) {
            return;
        }
        for (CSObj classObj : pts) {
            if (helper.isUnknownMetaObj(classObj)) {
                unsoundInvokes.add(invoke);
                return;
            }
        }
    }

    /**
     * Reports reflective calls that may be resolved unsoundly.
     */
    void reportUnsoundCalls() {
        if (!unsoundInvokes.isEmpty()) {
            logger.info("Unsound reflective calls:");
            unsoundInvokes.forEach(invoke ->
                    logger.info("[{}]{}", Reflections.getShortName(invoke), invoke));
        }
    }
    // ---------- Implementation of annotation guidance (ends) ----------
}
